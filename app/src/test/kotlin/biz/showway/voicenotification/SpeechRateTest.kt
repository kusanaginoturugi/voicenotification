package biz.showway.voicenotification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRateTest {
    @Test fun androidPercentAndFallback() {
        assertEquals(0.73f, SpeechRate.fromPercent(73), 0.0001f)
        assertEquals(1f, SpeechRate.fromPercent(0), 0f)
        assertEquals(1f, SpeechRate.fromPercent(-1), 0f)
    }

    @Test fun switchRestoresManualRateAndIgnoresCorrectionWhenOff() {
        assertEquals(1.3f, SpeechRate.effective(false, 1.3f, 0.73f, 1.2f), 0f)
        assertEquals(0.876f, SpeechRate.effective(true, 1.3f, 0.73f, 1.2f), 0.0001f)
        assertEquals(0.584f, SpeechRate.effective(true, 1.3f, 0.73f, 0.8f), 0.0001f)
        assertEquals(1.44f, SpeechRate.effective(true, 1.3f, 1.2f, 1.2f), 0.0001f)
    }

    @Test fun sliderHasNeutralCenterAndFineAdjustmentNearCenter() {
        assertEquals(0.5f, SpeechRate.correctionFromSlider(0f), 0f)
        assertEquals(1f, SpeechRate.correctionFromSlider(0.5f), 0f)
        assertEquals(2f, SpeechRate.correctionFromSlider(1f), 0f)
        val centerChange = SpeechRate.correctionFromSlider(0.6f) - 1f
        val edgeChange = SpeechRate.correctionFromSlider(1f) - SpeechRate.correctionFromSlider(0.9f)
        assertTrue(centerChange > 0 && centerChange < edgeChange / 10)
        for (step in 0..100) {
            val position = step / 100f
            val correction = SpeechRate.correctionFromSlider(position)
            assertEquals(position, SpeechRate.sliderFromCorrection(correction), 0.0005f)
            assertEquals(1f, correction * SpeechRate.correctionFromSlider(1f - position), 0.00001f)
            if (step > 0) assertTrue(correction > SpeechRate.correctionFromSlider((step - 1) / 100f))
        }
    }

    @Test fun invalidValuesNeverReachSynthesis() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, SpeechRate.effective(false, invalid, 1f, 1f), 0f)
            assertEquals(0.73f, SpeechRate.effective(true, 1f, 0.73f, invalid), 0f)
        }
        assertEquals(1f, SpeechRate.effective(true, 1f, Float.MAX_VALUE, 2f), 0f)
    }
}
