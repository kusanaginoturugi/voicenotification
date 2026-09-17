package biz.showway.voicenotification

import android.content.Context
import android.net.Uri

/** 内蔵チャイム。key が Prefs に保存される */
enum class Chime(val key: String, val label: String, val res: Int) {
    NONE("none", "なし", 0),
    TWO("two", "ちゃんちゃん", R.raw.chime_two),
    ARPEGGIO("arpeggio", "オルゴール", R.raw.chime_arpeggio),
    PINPON("pinpon", "ピンポンパンポン", R.raw.chime_pinpon),
    RADIO("radio", "ラジオ風ベル", R.raw.chime_radio),
    JNR_SHORT("jnr_short", "国鉄風オルゴール（短）", R.raw.chime_jnr_short),
    JNR("jnr", "国鉄風オルゴール（フル）", R.raw.chime_jnr),
    CUSTOM("custom", "ファイルを指定", 0);

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
