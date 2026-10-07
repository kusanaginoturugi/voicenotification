package biz.showway.voicenotification

import android.content.Context
import android.provider.Settings
import kotlin.math.ln
import kotlin.math.pow

/** Android の共通話速は 100 が等速。エンジン独自の設定は取得できない。 */
object SpeechRate {
    fun androidRate(context: Context): Float = try {
        fromPercent(Settings.Secure.getInt(context.contentResolver, Settings.Secure.TTS_DEFAULT_RATE, 100))
    } catch (_: SecurityException) {
        1.0f
    }

    internal fun fromPercent(percent: Int): Float = if (percent > 0) percent / 100f else 1f

    internal fun effective(sync: Boolean, manual: Float, android: Float, correction: Float): Float =
        if (sync) positive(positive(android) * positive(correction)) else positive(manual)

    private fun positive(value: Float): Float = value.takeIf { it.isFinite() && it > 0f } ?: 1f

    /** 中央が1倍。対数倍率を三次カーブにして、中央付近で細かく調整する。 */
    internal fun correctionFromSlider(position: Float): Float {
        val x = (position.coerceIn(0f, 1f) * 2f - 1f).toDouble()
        return 2.0.pow(x * x * x).toFloat()
    }

    internal fun sliderFromCorrection(correction: Float): Float {
        val logRatio = ln(positive(correction).coerceIn(0.5f, 2f).toDouble()) / ln(2.0)
        return ((Math.cbrt(logRatio) + 1.0) / 2.0).toFloat()
    }

    fun resolve(context: Context, prefs: Prefs): Float = effective(
        prefs.ttsSyncAndroidRate, prefs.ttsSpeed, androidRate(context), prefs.ttsRateCorrection,
    )
}
