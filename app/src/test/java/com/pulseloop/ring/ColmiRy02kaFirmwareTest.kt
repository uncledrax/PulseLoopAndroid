package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Colmi R02 on RY02KA hardware (sold as the Kogan Aura), stock and patched firmware.
 *
 * Patched firmware logs heart rate every 3 minutes (page 0 `15 00 27 03`, 39 pages) and stress / HRV
 * every 6 (`37|39 00 14 06`, 20 pages); PATCH3 and later also stop inventing a light/deep/REM split.
 * The stock firmware's sleep, read by emulating its own code: code 0 fills the gap between two
 * sleep periods joined into one night, and a restless night overruns the 40-segment arrays.
 */
class ColmiRy02kaFirmwareTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun page(vararg bytes: Int): ByteArray = ColmiPacket.frame(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun empty(op: UByte): ByteArray = byteArrayOf(op.toByte(), 0xFF.toByte())

    /** A fresh engine with today's HR day requested (eight empty activity days behind it). */
    private fun engineAtHeartRate(writer: RecordingWriter): ColmiSyncEngine {
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)) }
        return engine
    }

    private fun hrRequests(writer: RecordingWriter) =
        writer.commands.filter { it.isNotEmpty() && it[0].toUByte() == ColmiCommandID.SYNC_HEART_RATE }

    private fun minutesOfDay(events: List<RingDecodedEvent>, day: LocalDate): List<Int> {
        val base = day.atStartOfDay(zone).toInstant()
        return events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
            .map { ((it.timestamp.toEpochMilli() - base.toEpochMilli()) / 60_000L).toInt() }
    }

    // MARK: Paged history at the patched cadence

    @Test
    fun `a 39-page 3-minute HR day runs to its last page instead of stopping at page 23`() {
        val writer = RecordingWriter()
        val engine = engineAtHeartRate(writer)
        val requestsBefore = hrRequests(writer).size
        engine.handleHistoryFrame(page(0x15, 0, 39, 3))
        engine.handleHistoryFrame(page(0x15, 1, 0, 0, 0, 0, *IntArray(9) { 60 }))
        for (n in 2..37) engine.handleHistoryFrame(page(0x15, n, *IntArray(13) { 61 }))
        assertEquals("no next-day request before the announced last page", requestsBefore, hrRequests(writer).size)

        val today = LocalDate.now(zone)
        val last = engine.handleHistoryFrame(page(0x15, 38, 62, 63, 64))
        assertEquals(listOf(1431, 1434, 1437), minutesOfDay(last, today))
        assertEquals("page 38 completes the day and asks for yesterday", requestsBefore + 1, hrRequests(writer).size)
    }

    @Test
    fun `a stock HR day without a header still ends at page 23`() {
        val writer = RecordingWriter()
        val engine = engineAtHeartRate(writer)
        val requestsBefore = hrRequests(writer).size
        engine.handleHistoryFrame(page(0x15, 1, 0, 0, 0, 0, *IntArray(9) { 60 }))
        for (n in 2..23) engine.handleHistoryFrame(page(0x15, n, *IntArray(13) { 61 }))
        assertEquals(requestsBefore + 1, hrRequests(writer).size)
    }

    @Test
    fun `six-minute stress pages decode on a six-minute grid`() {
        val day = LocalDate.now(zone)
        val p2 = ColmiDecoder.decodeHistory(page(0x37, 2, 40), day = day, zone = zone, slotMinutes = 6)
            .filterIsInstance<RingDecodedEvent.StressSample>()
        assertEquals(72L, (p2.single().timestamp.toEpochMilli() - day.atStartOfDay(zone).toInstant().toEpochMilli()) / 60_000L)
    }

    // MARK: Sleep

    private fun sleepFrame(start: Int, end: Int, vararg segments: Pair<Int, Int>): ByteArray {
        val day = mutableListOf(0, segments.size * 2 + 4, start and 0xFF, start shr 8, end and 0xFF, end shr 8)
        segments.forEach { (code, minutes) -> day += code; day += minutes }
        val payload = listOf(1) + day
        val len = payload.size
        return (listOf(0xBC, ColmiCommandID.BIG_DATA_SLEEP.toInt(), len and 0xFF, len shr 8, 0, 0) + payload)
            .map { it.toByte() }.toByteArray()
    }

    private fun stages(events: List<RingDecodedEvent>): List<SleepStage>? =
        (events.singleOrNull() as? RingDecodedEvent.SleepTimeline)?.stages

    @Test
    fun `the gap code between joined sleep periods is awake, not unknown`() {
        assertEquals(SleepStage.AWAKE, ColmiDecoder.sleepStage(0x00u))
        assertEquals(SleepStage.AWAKE, ColmiDecoder.sleepStage(0x05u))
        assertEquals(SleepStage.LIGHT, ColmiDecoder.sleepStage(0x02u))
        assertEquals(SleepStage.UNKNOWN, ColmiDecoder.sleepStage(0x09u))
    }

    @Test
    fun `on firmware without measured stages light deep and REM all mean asleep`() {
        for (code in listOf<UByte>(0x02u, 0x03u, 0x04u)) {
            assertEquals(SleepStage.UNKNOWN, ColmiDecoder.sleepStage(code, stagesUnmeasured = true))
        }
        assertEquals(SleepStage.AWAKE, ColmiDecoder.sleepStage(0x05u, stagesUnmeasured = true))
        assertEquals(SleepStage.AWAKE, ColmiDecoder.sleepStage(0x00u, stagesUnmeasured = true))
    }

    @Test
    fun `firmware revision detection`() {
        assertTrue(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_PATCH3"))
        assertTrue(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_PATCH12\n"))
        assertFalse(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_PATCH2"))
        assertFalse(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_250603"))
        assertFalse(ColmiFirmware.reportsUnmeasuredSleepStages("RT02R_3.11.00_PATCH3"))
    }

    @Test
    fun `the driver follows this connection's firmware revision`() {
        val driver = ColmiDriver { }
        // 23:00 → 00:20, 80 minutes of segments.
        val night = sleepFrame(1380, 20, 2 to 40, 3 to 30, 5 to 5, 2 to 5)

        driver.connectionDidStart()
        driver.firmwareRevisionDidUpdate("RY02KA_3.00.31_PATCH3")
        driver.firmwareRevisionDidUpdate("V1.0")   // 2A28 arriving second must not clear it
        assertEquals(setOf(SleepStage.UNKNOWN, SleepStage.AWAKE), stages(driver.ingest(night, ColmiUUIDs.NOTIFY_V2))?.toSet())

        driver.connectionDidStart()                // reflashed to stock, reconnect
        driver.firmwareRevisionDidUpdate("RY02KA_3.00.31_250603")
        assertEquals(setOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.AWAKE),
            stages(driver.ingest(night, ColmiUUIDs.NOTIFY_V2))?.toSet())
    }

    @Test
    fun `a night whose segments cover its span is kept`() {
        // 23:00 → 07:29 = 509 min: the night emulated from the stock firmware.
        val durations = listOf(12, 28, 18, 21, 37, 17, 31, 33, 14, 28, 27, 29, 54, 26, 35, 16, 38, 2, 28, 15)
        val codes = listOf(2, 3, 2, 3, 2, 4, 2, 3, 4, 2, 0, 4, 2, 3, 2, 4, 3, 5, 2, 2)
        val decoded = stages(ColmiDecoder.decodeBigData(sleepFrame(1380, 449, *codes.zip(durations).toTypedArray()), zone = zone))
        assertEquals(509, decoded?.size)
        assertEquals("27 min gap + 2 min awake", 29, decoded?.count { it == SleepStage.AWAKE })
    }

    @Test
    fun `a night whose segments do not cover its span is dropped`() {
        // A restless night overran the ring's segment arrays: 421 of 509 minutes survive.
        val frame = sleepFrame(1380, 449, 2 to 200, 5 to 21, 3 to 200)
        assertTrue(ColmiDecoder.decodeBigData(frame, zone = zone).isEmpty())
    }

    @Test
    fun `span check allows rounding slack`() {
        assertTrue(ColmiDecoder.segmentsMatchSpan(404, 150, 554))
        assertTrue(ColmiDecoder.segmentsMatchSpan(60, 480, 540))
        assertTrue(ColmiDecoder.segmentsMatchSpan(508, 1380, 449))
        assertFalse(ColmiDecoder.segmentsMatchSpan(421, 1380, 449))
        assertTrue("out-of-range ends aren't judged", ColmiDecoder.segmentsMatchSpan(60, 1468, 60))
    }

    // MARK: Model

    @Test
    fun `Kogan Aura resolves to its own card on the QRing Colmi driver`() {
        val model = WearableModel.modelForAdvertisedName("Kogan Aura")
        assertEquals("kogan-aura", model?.id)
        assertEquals(RingDeviceType.COLMI_R02, model?.family)
        assertFalse(Regex(WearableModel.SMARTHEALTH_NAME_PATTERN).containsMatchIn("Kogan Aura"))
        assertNull(WearableModel.modelForAdvertisedName("Kogan Aura 2"))
    }

    @Suppress("unused")
    private val clockAnchor = Instant.EPOCH
}
