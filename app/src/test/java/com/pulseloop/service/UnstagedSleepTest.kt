package com.pulseloop.service

import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import org.junit.Assert.*
import org.junit.Test

/**
 * A night recorded as asleep / awake only — patched RY02KA firmware, whose stock light/deep/REM split
 * is chosen by `rand()` — decodes to [SleepStage.UNKNOWN] blocks. It must be scored and described on
 * what was measured (duration, wakefulness), never on a stage mix nobody measured.
 */
class UnstagedSleepTest {

    private val start = 1_760_000_000_000L

    private fun session(asleepMin: Int, spanMin: Int) = SleepSessionEntity(
        date = start, startAt = start, endAt = start + spanMin * 60_000L, totalMinutes = asleepMin,
    )

    private fun blocks(sessionId: String, vararg runs: Pair<SleepStage, Int>): List<SleepStageBlockEntity> {
        var minute = 0
        return runs.map { (stage, minutes) ->
            SleepStageBlockEntity(
                sessionId = sessionId, startAt = start + minute * 60_000L, startMinute = minute,
                durationMinutes = minutes, stageRaw = stage.name,
            ).also { minute += minutes }
        }
    }

    @Test
    fun `an asleep-awake night is recognised and a staged one is not`() {
        val unstaged = blocks("a", SleepStage.UNKNOWN to 300, SleepStage.AWAKE to 10, SleepStage.UNKNOWN to 160)
        assertEquals(460, unstagedMinutes(unstaged))
        assertTrue(stagesUnmeasured(unstaged))
        assertEquals(460, asleepMinutes(unstaged))

        val staged = blocks("b", SleepStage.LIGHT to 300, SleepStage.DEEP to 90, SleepStage.UNKNOWN to 5)
        assertFalse(stagesUnmeasured(staged))
        assertFalse(stagesUnmeasured(emptyList()))
    }

    @Test
    fun `an unstaged night is scored on duration and awake time`() {
        val s = session(asleepMin = 460, spanMin = 480)
        val b = blocks(s.id, SleepStage.UNKNOWN to 300, SleepStage.AWAKE to 20, SleepStage.UNKNOWN to 160)
        val score = SleepScore.calculate(s, b)
        assertTrue("7h40 asleep with 4% awake shouldn't be marked down for 'no deep sleep' (got ${score.score})",
            score.score >= 85)
        assertEquals(4, score.awakePct)
        assertEquals(0, score.deepPct)
    }

    @Test
    fun `the day coach doesn't talk about stages it didn't measure`() {
        val s = session(asleepMin = 460, spanMin = 480)
        val b = blocks(s.id, SleepStage.UNKNOWN to 300, SleepStage.AWAKE to 20, SleepStage.UNKNOWN to 160)
        val coach = SleepInsights.dayCoach(s, b, activitySteps = null)
        val text = (coach.headline + coach.body + coach.chips.joinToString()).lowercase()
        assertFalse(text, text.contains("deep"))
        assertTrue(coach.chips.contains("Stages not measured"))
    }

    @Test
    fun `no deep-band score is stored for an unstaged night`() {
        assertNull(sleepStageScore(deepMin = 0, totalMin = 460, stagesUnmeasured = true))
        assertEquals(40, sleepStageScore(deepMin = 0, totalMin = 460))
    }
}
