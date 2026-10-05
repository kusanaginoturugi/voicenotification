package biz.showway.voicenotification

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class SpeakerVolumeTest {
    @Test fun maximumVolumeUsesConfiguredCeiling() {
        assertEquals(0.15f, SpeakerVolume.gain(0.15f, 0f, 0f), 0.00001f)
        assertEquals(0.15f, SpeakerVolume.gain(0.15f, -3f, -3f), 0.00001f)
    }

    @Test fun quietSystemVolumeIsNotAttenuatedAgain() {
        assertEquals(1f, SpeakerVolume.gain(0.15f, -40f, 0f), 0f)
    }

    @Test fun combinedGainNeverExceedsCeilingAndNeverAmplifies() {
        for (maximum in listOf(0f, -3f)) {
            for (db in -80..0) {
                val current = maximum + db
                val gain = SpeakerVolume.gain(0.15f, current, maximum)
                val combined = 10.0.pow((current - maximum) / 20.0) * gain
                assertTrue(gain in 0f..1f)
                assertTrue(combined <= 0.150001)
                if (gain < 1f) assertEquals(0.15, combined, 0.000001)
            }
        }
    }

    @Test fun muteAndUnavailableCurvesRemainSafe() {
        assertEquals(0f, SpeakerVolume.gain(0.15f, Float.NEGATIVE_INFINITY, 0f), 0f)
        assertEquals(0.15f, SpeakerVolume.gain(0.15f, Float.NaN, 0f), 0f)
        assertEquals(0.15f, SpeakerVolume.gain(0.15f, 0f, Float.NaN), 0f)
        assertEquals(0f, SpeakerVolume.gain(0f, 0f, 0f), 0f)
    }
}
