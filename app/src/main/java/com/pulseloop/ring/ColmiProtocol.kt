package com.pulseloop.ring

/**
 * Ported from [ColmiUUIDs] and [ColmiCommandID] in ColmiProtocol.swift.
 * Colmi R02 (Yawell) protocol primitives: UUIDs, opcodes, 16-byte checksummed framing.
 */
object ColmiUUIDs {
    const val SERVICE_V1 = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
    const val SERVICE_V2 = "de5bf728-d711-4e47-af26-65e3012a5dc7"
    const val WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val COMMAND = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
    const val NOTIFY_V1 = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY_V2 = "de5bf729-d711-4e47-af26-65e3012a5dc7"
}

object ColmiCommandID {
    const val SET_DATE_TIME: UByte = 0x01u
    const val BATTERY: UByte = 0x03u
    const val PHONE_NAME: UByte = 0x04u
    const val DISPLAY_PREF: UByte = 0x05u
    const val POWER_OFF: UByte = 0x08u
    const val PREFERENCES: UByte = 0x0Au
    const val SYNC_HEART_RATE: UByte = 0x15u
    const val AUTO_HR_PREF: UByte = 0x16u
    const val REALTIME_HEART_RATE: UByte = 0x1Eu
    const val REALTIME_HEART_RATE_ERROR: UByte = 0x9Eu
    const val GOALS: UByte = 0x21u
    const val AUTO_SPO2_PREF: UByte = 0x2Cu
    const val PACKET_SIZE: UByte = 0x2Fu
    const val AUTO_STRESS_PREF: UByte = 0x36u
    const val SYNC_STRESS: UByte = 0x37u
    const val AUTO_HRV_PREF: UByte = 0x38u
    const val SYNC_HRV: UByte = 0x39u
    const val AUTO_TEMP_PREF: UByte = 0x3Au
    // Device-support / capability bitfield read (QRing DeviceSupportReq, opcode 60). The
    // reply's byte[1] carries feature bits; bit 3 (0x08) = supportBlePair — the ring wants
    // an OS-level bond. Free opcode in this map (see docs/qring-ble-adoption.md).
    const val DEVICE_SUPPORT: UByte = 0x3Cu
    const val SYNC_ACTIVITY: UByte = 0x43u
    const val BP_READ: UByte = 0x14u
    const val BP_CONFIRM: UByte = 0x0Eu
    const val FIND_DEVICE: UByte = 0x50u
    const val MANUAL_HEART_RATE: UByte = 0x69u   // CMD_START_REAL_TIME (105)
    const val REALTIME_STOP: UByte = 0x6Au       // CMD_STOP_REAL_TIME (106)
    /**
     * Phone-driven sport session (`PhoneSportReq`, opcode 119): `[0x77, status, sportType]`. This
     * is how the QRing app runs a live activity — it never touches the realtime-HR commands there.
     * While the session is on, the ring pushes [SPORT_NOTIFY] on its own cadence (issue #64).
     * Command channel only; [BIG_DATA_INTERVAL_TEMPERATURE] is a big-data *action* that happens
     * to share the number on the other characteristic.
     */
    const val PHONE_SPORT: UByte = 0x77u
    /** Sport telemetry pushed by the ring during a [PHONE_SPORT] session (`DeviceNotifyRsp`,
     *  opcode 120): `[0x78, dataType, status, durMinHi, durMinLo, bpm, steps×3, metres×3, cal×3]`
     *  (`SportRunningActivity.MyDeviceNotifyListener`). */
    const val SPORT_NOTIFY: UByte = 0x78u
    // PhoneSportReq statuses (SportRunningActivity / SportPrepareActivity).
    const val SPORT_START: UByte = 0x01u
    const val SPORT_PAUSE: UByte = 0x02u
    const val SPORT_RESUME: UByte = 0x03u
    const val SPORT_STOP: UByte = 0x04u
    /** Status byte the ring reports in [SPORT_NOTIFY] when it ended the session itself. */
    const val SPORT_ENDED_BY_RING: UByte = 0x03u
    // PhoneSportReq sport types (SportRunningActivity.sportMap → strings.xml).
    const val SPORT_TYPE_WALK: UByte = 0x04u
    const val SPORT_TYPE_RUN: UByte = 0x07u
    const val SPORT_TYPE_HIKE: UByte = 0x08u
    const val SPORT_TYPE_CYCLE: UByte = 0x09u
    const val SPORT_TYPE_OTHER: UByte = 0x0Au
    const val SPORT_TYPE_YOGA: UByte = 0x16u
    const val NOTIFICATION: UByte = 0x73u
    const val BIG_DATA_V2: UByte = 0xBCu
    const val FACTORY_RESET: UByte = 0xFFu

    const val PREF_READ: UByte = 0x01u
    const val PREF_WRITE: UByte = 0x02u
    const val PREF_DELETE: UByte = 0x03u

    // Real-time measurement reading types (0x69/0x6A payload byte 0).
    // From the colmi_r02_client RealTimeReading enum.
    const val RT_HEART_RATE: UByte = 0x01u
    const val RT_SPO2: UByte = 0x03u
    // On-demand HRV / stress / temperature (QRing `StartHeartRateReq.TYPE_PRESSURE` = 8,
    // `TYPE_HRV` = 10, `TYPE_BODY_TEMPERATURE` = 11), confirmed on a Ring 2 Pro capture.
    const val RT_STRESS: UByte = 0x08u
    const val RT_HRV: UByte = 0x0Au
    const val RT_TEMPERATURE: UByte = 0x0Bu

    fun readingType(vital: SpotVital): UByte = when (vital) {
        SpotVital.HRV -> RT_HRV
        SpotVital.STRESS -> RT_STRESS
        SpotVital.TEMPERATURE -> RT_TEMPERATURE
    }

    fun spotVital(readingType: UByte): SpotVital? = SpotVital.entries.firstOrNull { readingType(it) == readingType }

    // Real-time start action (0x69 payload byte 1). Stopping does NOT use an action byte
    // on 0x69 — it must be the separate CMD_STOP_REAL_TIME (0x6A) frame.
    const val RT_ACTION_START: UByte = 0x01u

    // 0x73 notification subtypes
    const val NOTIF_NEW_HR: UByte = 0x01u
    const val NOTIF_NEW_SPO2: UByte = 0x03u
    const val NOTIF_NEW_STEPS: UByte = 0x04u
    const val NOTIF_BATTERY: UByte = 0x0Cu
    const val NOTIF_LIVE_ACTIVITY: UByte = 0x12u

    // Big-data types
    const val BIG_DATA_BLOOD_SUGAR: UByte = 0x47u
    const val BIG_DATA_TEMPERATURE: UByte = 0x25u
    const val BIG_DATA_SLEEP: UByte = 0x27u
    const val BIG_DATA_SPO2: UByte = 0x2Au
    /** Nap/lunch sleep (QRing action 62). Never requested — rings with nap support emit it
     *  spontaneously alongside the action-39 reply to [ColmiEncoder.bigDataSleep]. */
    const val BIG_DATA_SLEEP_LUNCH: UByte = 0x3Eu
    /** Interval temperature history (QRing action 119) — the modern per-day, packet-indexed
     *  path used instead of [BIG_DATA_TEMPERATURE] when the 0x3C reply sets supportIntervalTemp. */
    const val BIG_DATA_INTERVAL_TEMPERATURE: UByte = 0x77u

    // Sleep stage types
    /** Fills the time between two sleep periods the ring joined into one night — an awakening of
     *  10+ minutes (plus its 16-minute re-detection hold-off), or the ring off. Never sleep. */
    const val SLEEP_GAP: UByte = 0x00u
    const val SLEEP_LIGHT: UByte = 0x02u
    const val SLEEP_DEEP: UByte = 0x03u
    const val SLEEP_REM: UByte = 0x04u
    const val SLEEP_AWAKE: UByte = 0x05u
}

/**
 * Ported from [ColmiPacket] in ColmiProtocol.swift.
 * A 16-byte Colmi frame: 15 content bytes + trailing checksum.
 */
data class ColmiPacket(val bytes: ByteArray) {
    companion object {
        /** Build a framed 16-byte packet from ≤15 content bytes + checksum. */
        fun frame(content: ByteArray): ByteArray {
            val buffer = ByteArray(16)
            val count = minOf(content.size, 15)
            content.copyInto(buffer, 0, 0, count)
            val checksum = (0 until 15).sumOf { buffer[it].toInt() and 0xFF } and 0xFF
            buffer[15] = checksum.toByte()
            return buffer
        }

        /** Validate + decode a 16-byte frame. Returns null on bad length or checksum. */
        fun validating(data: ByteArray): ColmiPacket? {
            if (data.size != 16) return null
            val checksum = (0 until 15).sumOf { data[it].toInt() and 0xFF } and 0xFF
            if (checksum != (data[15].toInt() and 0xFF)) return null
            return ColmiPacket(data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColmiPacket) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Ported from [ColmiBytes] in ColmiProtocol.swift.
 */
object ColmiBytes {
    fun u16(a: UByte, b: UByte): Int = a.toInt() or (b.toInt() shl 8)
    fun u32(a: UByte, b: UByte, c: UByte, d: UByte): Int =
        a.toInt() or (b.toInt() shl 8) or (c.toInt() shl 16) or (d.toInt() shl 24)
    fun u24(a: UByte, b: UByte, c: UByte): Int =
        a.toInt() or (b.toInt() shl 8) or (c.toInt() shl 16)
    fun u24be(a: UByte, b: UByte, c: UByte): Int =
        (a.toInt() shl 16) or (b.toInt() shl 8) or c.toInt()
}

/** Facts about specific Colmi firmware builds, keyed on the Device Information firmware revision. */
object ColmiFirmware {
    /** `n` of patched RY02KA firmware (`RY02KA_<version>_PATCH<n>`); null for stock firmware or any other ring. */
    fun ry02kaPatchLevel(firmwareRevision: String): Int? {
        val revision = firmwareRevision.trim()
        if (!revision.startsWith("RY02KA_")) return null
        val at = revision.lastIndexOf("_PATCH")
        if (at < 0) return null
        return revision.substring(at + "_PATCH".length).toIntOrNull()
    }

    /**
     * Patched RY02KA firmware (`RY02KA_<version>_PATCH<n>`, n ≥ 3) sends sleep code 2 for every
     * sleeping minute instead of the stock firmware's light / deep / REM split, which the stock
     * firmware chooses with `rand()` — the ring only measures sleep onset, waking, and awake spells
     * of 2+ minutes. On that firmware codes 2/3/4 mean "asleep, stage not measured".
     */
    fun reportsUnmeasuredSleepStages(firmwareRevision: String): Boolean =
        (ry02kaPatchLevel(firmwareRevision) ?: 0) >= 3

    /**
     * How often the ring really logs heart rate at an all-day interval setting, in seconds, when
     * that differs from the setting; null when the ring logs at the interval it was sent.
     *
     * From PATCH2 on, RY02KA firmware counts the `0x16` interval in 36-second units instead of
     * minutes, so the app's 5…60 minutes run every 3…36 minutes (5 → 3, 10 → 6, 60 → 36). The setting
     * sent to the ring is unchanged; only what the app tells the user it means.
     */
    fun loggedHeartRateIntervalSeconds(settingMinutes: Int, firmwareRevision: String?): Int? {
        if (firmwareRevision == null || (ry02kaPatchLevel(firmwareRevision) ?: 0) < 2) return null
        return settingMinutes * 36
    }

    /** The stress / HRV logging interval, in minutes, of firmware that fixes it independently of any
     *  setting: every 6 minutes from RY02KA PATCH2 on. null for stock firmware (stress twice an hour,
     *  HRV hourly) and other rings. */
    fun loggedStressAndHrvIntervalMinutes(firmwareRevision: String?): Int? {
        if (firmwareRevision == null || (ry02kaPatchLevel(firmwareRevision) ?: 0) < 2) return null
        return 6
    }

    /** "3 min" / "36 s" — an interval in the units the Measurement screen shows. */
    fun intervalLabel(seconds: Int): String =
        if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"
}
