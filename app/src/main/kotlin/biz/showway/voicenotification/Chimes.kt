package biz.showway.voicenotification

import android.content.Context
import android.net.Uri

/** 内蔵チャイム。key が Prefs に保存される */
enum class Chime(val key: String, val labelRes: Int, val res: Int) {
    NONE("none", R.string.chime_none, 0),
    TWO("two", R.string.chime_two, R.raw.chime_two),
    ARPEGGIO("arpeggio", R.string.chime_arpeggio, R.raw.chime_arpeggio),
    PINPON("pinpon", R.string.chime_pinpon, R.raw.chime_pinpon),
    RADIO("radio", R.string.chime_radio, R.raw.chime_radio),
    JNR_SHORT("jnr_short", R.string.chime_jnr_short, R.raw.chime_jnr_short),
    JNR("jnr", R.string.chime_jnr, R.raw.chime_jnr),
    CUSTOM("custom", R.string.chime_custom, 0);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: TWO

        /** 設定に従って再生すべき URI。鳴らさないなら null */
        fun uriFor(context: Context, prefs: Prefs): Uri? = when (val c = of(prefs.newsChime)) {
            NONE -> null
            CUSTOM -> prefs.newsChimeUri?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            else -> Uri.parse("android.resource://${context.packageName}/${c.res}")
        }
    }
}
