package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * The Colmi stack across a dropped link. The client builds a fresh driver (and engine) per
 * connection and never destroys the old one, so the old engine's stage watchdog, live-HR keepalive
 * and sport watchdog must stop when its driver hears `connectionDidEnd` — or they keep writing into
 * the next connection's queue. Replies that don't answer the stage in flight are decoded where they
 * can still be dated, but never move the machine; a run the ring answered nothing in is not "done".
 */
class ColmiLinkLifecycleTest {

    private class RecordingWriter : RingCommandWriter {
        val commands: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    private fun page(vararg bytes: Int): ByteArray = ColmiPacket.frame(ByteArray(bytes.size) { bytes[it].toByte() })
    private fun empty(op: UByte): ByteArray = byteArrayOf(op.toByte(), 0xFF.toByte())

    /** History requests in send order: the paged opcode, or the big-data type. */
    private fun requests(writer: RecordingWriter): List<UByte> = synchronized(writer.commands) {
        writer.commands.mapNotNull { c ->
            val op = c.firstOrNull()?.toUByte() ?: return@mapNotNull null
            when {
                op == ColmiCommandID.BIG_DATA_V2 -> c.getOrNull(1)?.toUByte()
                ColmiSyncEngine.isHistoryOpcode(op) -> op
                else -> null
            }
        }
    }

    private fun engine(writer: RecordingWriter, watchdogMs: Long? = null) =
        ColmiSyncEngine(writer, ColmiDecoder).also { e ->
            watchdogMs?.let { e.watchdogTimeoutMs = it; e.activityWatchdogTimeoutMs = it }
        }

    private fun answerThroughStress(engine: ColmiSyncEngine) {
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)) }
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_HEART_RATE)) }
        repeat(7) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_STRESS)) }
    }

    private fun answerFullRun(engine: ColmiSyncEngine) {
        answerThroughStress(engine)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)
        repeat(7) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_HRV)) }
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_TEMPERATURE)
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    // MARK: Run outcome

    @Test
    fun `an answered run ends done`() {
        val engine = engine(RecordingWriter())
        engine.runStartup()
        answerFullRun(engine)
        assertEquals("done", engine.lastRunOutcome)
    }

    @Test
    fun `a run the watchdog walks in silence ends incomplete`() {
        val writer = RecordingWriter()
        val engine = engine(writer, watchdogMs = 5)
        engine.runStartup()
        waitUntil(5_000) { engine.lastRunOutcome != null }
        assertEquals("no reply at all is not a finished sync", "incomplete", engine.lastRunOutcome)
        assertEquals("the watchdog walked every stage", ColmiCommandID.BIG_DATA_TEMPERATURE, requests(writer).last())
    }

    @Test
    fun `a late big-data reply during a run counts as a reply`() {
        val engine = engine(RecordingWriter(), watchdogMs = 5)
        engine.runStartup()
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)   // not asked for yet; the driver stored it
        waitUntil(5_000) { engine.lastRunOutcome != null }
        assertEquals("done", engine.lastRunOutcome)
    }

    // MARK: Disconnect

    @Test
    fun `disconnect stops the stage watchdog`() {
        val writer = RecordingWriter()
        val engine = engine(writer, watchdogMs = 5)
        engine.runStartup()
        engine.connectionDidEnd()
        val sent = writer.commands.size
        Thread.sleep(300)   // ~60 watchdog periods
        assertEquals("nothing is written into the next connection", sent, writer.commands.size)
        assertNull("an interrupted run is neither done nor incomplete", engine.lastRunOutcome)
    }

    @Test
    fun `frames arriving after disconnect do not move the machine`() {
        val writer = RecordingWriter()
        val engine = engine(writer)
        engine.runStartup()
        engine.connectionDidEnd()
        val sent = writer.commands.size
        assertTrue(engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)).isEmpty())
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)
        assertEquals(sent, writer.commands.size)
    }

    @Test
    fun `the live-HR streams end with the link`() {
        val writer = RecordingWriter()
        val engine = engine(writer)
        engine.startHeartRate()
        engine.measureHeartRateSpot()
        engine.connectionDidEnd()
        val sent = writer.commands.size
        engine.stopHeartRate()   // a workout or spot measurement ending after the link dropped
        assertEquals("there is nothing left to stop", sent, writer.commands.size)
    }

    @Test
    fun `the driver forwards the link end to its engine and drops a half-received frame`() {
        val writer = RecordingWriter()
        val driver = ColmiDriver(writer)
        driver.makeSyncEngine().runStartup()
        // One 60-minute night, 23:00 → 00:00 (two 30-minute segments).
        val payload = listOf(1, 0, 8, 0x64, 0x05, 0x00, 0x00, 2, 30, 3, 30)
        val frame = (listOf(0xBC, ColmiCommandID.BIG_DATA_SLEEP.toInt(), payload.size, 0, 0, 0) + payload)
            .map { it.toByte() }.toByteArray()
        assertTrue(driver.ingest(frame.copyOfRange(0, 9), ColmiUUIDs.NOTIFY_V2).isEmpty())

        driver.connectionDidEnd()
        val sent = writer.commands.size
        assertTrue("the old link's half frame is gone",
            driver.ingest(frame.copyOfRange(9, frame.size), ColmiUUIDs.NOTIFY_V2).none { it is RingDecodedEvent.SleepTimeline })
        assertEquals("the engine stopped with the link", sent, writer.commands.size)

        driver.connectionDidStart()
        assertTrue(driver.ingest(frame, ColmiUUIDs.NOTIFY_V2).any { it is RingDecodedEvent.SleepTimeline })
    }

    // MARK: Replies outside their stage

    @Test
    fun `a late page from a skipped stage is dropped`() {
        val writer = RecordingWriter()
        val engine = engine(writer)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)) }
        val sent = requests(writer).size   // HR day 0 requested

        assertTrue(engine.handleHistoryFrame(page(0x37, 1, 0, *IntArray(12) { 40 })).isEmpty())
        assertTrue(engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)).isEmpty())
        assertEquals("neither advances HR's day", sent, requests(writer).size)

        engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_HEART_RATE))
        assertEquals("HR's own reply still advances it", sent + 1, requests(writer).size)
        assertEquals(ColmiCommandID.SYNC_HEART_RATE, requests(writer).last())
    }

    @Test
    fun `a late page after the move to big data is still dated to its own day`() {
        val writer = RecordingWriter()
        val engine = engine(writer)
        val zone = ZoneId.systemDefault()
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_ACTIVITY)) }
        repeat(8) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_HEART_RATE)) }
        repeat(6) { engine.handleHistoryFrame(empty(ColmiCommandID.SYNC_STRESS)) }
        // Day 6: packets 2–4 arrive (4 ends the day), packet 1 straggles in after SpO2 was requested.
        for (n in 2..4) engine.handleHistoryFrame(page(0x37, n, *IntArray(if (n == 4) 10 else 13) { 40 }))
        assertEquals(ColmiCommandID.BIG_DATA_SPO2, requests(writer).last())
        val sent = requests(writer).size

        val late = engine.handleHistoryFrame(page(0x37, 1, 6, *IntArray(12) { 40 }))
        val day6 = LocalDate.now(zone).minusDays(6)
        val stamps = late.filterIsInstance<RingDecodedEvent.StressSample>()
        assertEquals(12, stamps.size)
        assertTrue(stamps.all { it.timestamp.atZone(zone).toLocalDate() == day6 })
        assertEquals("a late page never advances a stage it isn't in", sent, requests(writer).size)
    }

    @Test
    fun `a second SpO2 reply does not request sleep twice`() {
        val writer = RecordingWriter()
        val engine = engine(writer)
        engine.runStartup()
        answerThroughStress(engine)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)   // → sleep requested
        val sent = requests(writer).size
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)
        assertEquals(sent, requests(writer).size)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_TEMPERATURE)
        assertNull("a stray temperature reply doesn't end the run early", engine.lastRunOutcome)
    }

    // MARK: Patched-firmware intervals

    @Test
    fun `patch level parsing`() {
        assertEquals(2, ColmiFirmware.ry02kaPatchLevel("RY02KA_3.00.31_PATCH2"))
        assertEquals(12, ColmiFirmware.ry02kaPatchLevel(" RY02KA_3.00.31_PATCH12\n"))
        assertNull(ColmiFirmware.ry02kaPatchLevel("RY02KA_3.00.31_250603"))
        assertNull(ColmiFirmware.ry02kaPatchLevel("RT02R_3.11.00_PATCH3"))
    }

    @Test
    fun `heart-rate interval on PATCH2 and later`() {
        for (revision in listOf("RY02KA_3.00.31_PATCH2", "RY02KA_3.00.31_PATCH3")) {
            assertEquals(180, ColmiFirmware.loggedHeartRateIntervalSeconds(5, revision))
            assertEquals(360, ColmiFirmware.loggedHeartRateIntervalSeconds(10, revision))
            assertEquals(2160, ColmiFirmware.loggedHeartRateIntervalSeconds(60, revision))
            assertEquals(6, ColmiFirmware.loggedStressAndHrvIntervalMinutes(revision))
        }
        assertEquals("3 min", ColmiFirmware.intervalLabel(180))
        assertEquals("36 s", ColmiFirmware.intervalLabel(36))
    }

    @Test
    fun `stock, PATCH1 and other rings keep the setting`() {
        for (revision in listOf("RY02KA_3.00.31_PATCH1", "RY02KA_3.00.31_250603", "RT02R_3.11.00_PATCH3", null)) {
            assertNull(ColmiFirmware.loggedHeartRateIntervalSeconds(5, revision))
            assertNull(ColmiFirmware.loggedStressAndHrvIntervalMinutes(revision))
        }
        assertTrue(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_PATCH3"))
        assertFalse(ColmiFirmware.reportsUnmeasuredSleepStages("RY02KA_3.00.31_PATCH2"))
    }
}
