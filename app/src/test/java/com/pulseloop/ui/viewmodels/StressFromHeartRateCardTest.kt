package com.pulseloop.ui.viewmodels

import com.pulseloop.service.MetricKind
import com.pulseloop.service.UserPhysiologyProfile
import com.pulseloop.service.VitalSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ring that calculates its stress figure from heart rate (RY02KA, the Kogan Aura) has its stress
 * card flagged estimated. That flag is what puts the ESTIMATED chip on the Vitals card and
 * "Estimated from heart rate" on the Today tile, the two surfaces that draw from a [VitalCardState].
 */
class StressFromHeartRateCardTest {

    private val profile = UserPhysiologyProfile()
    private val samples = listOf(VitalSample(1_000L, 42.0), VitalSample(2_000L, 45.0))

    private fun card(metric: MetricKind, stressFromHeartRate: Boolean): VitalCardState =
        VitalsCardFactory.card(
            metric,
            VitalsCardFactory.Inputs(
                stress = samples, fatigue = samples,
                stressFromHeartRate = stressFromHeartRate, nowMs = 3_000L,
            ),
            profile,
        )

    @Test
    fun `stress the ring computed from heart rate is flagged estimated`() {
        assertTrue(card(MetricKind.STRESS, stressFromHeartRate = true).isEstimated)
    }

    @Test
    fun `stress the ring measured is not`() {
        assertFalse(card(MetricKind.STRESS, stressFromHeartRate = false).isEstimated)
    }

    @Test
    fun `the flag belongs to the stress card alone`() {
        // Fatigue shares the score-card builder; the ring's stress formula says nothing about it.
        assertFalse(card(MetricKind.FATIGUE, stressFromHeartRate = true).isEstimated)
    }
}
