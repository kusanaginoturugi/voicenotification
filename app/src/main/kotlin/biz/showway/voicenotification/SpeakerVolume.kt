package biz.showway.voicenotification

import kotlin.math.log10
import kotlin.math.pow

/** 最大システム音量時の振幅を基準にした上限。小さい音量をさらに減衰させない。 */
object SpeakerVolume {
    fun gain(limit: Float, currentDb: Float, maximumDb: Float): Float {
        val safeLimit = if (limit.isFinite()) limit.coerceIn(0f, 1f) else 0.15f
        if (safeLimit == 0f) return 0f
        if (currentDb == Float.NEGATIVE_INFINITY) return 0f
        if (!currentDb.isFinite() || !maximumDb.isFinite()) return safeLimit
        val ceilingDb = maximumDb + 20.0 * log10(safeLimit.toDouble())
        return 10.0.pow((ceilingDb - currentDb) / 20.0).coerceIn(0.0, 1.0).toFloat()
    }
}
