package biz.showway.voicenotification

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    var packages: Set<String>
        get() = sp.getStringSet("packages", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("packages", v.toSet()).apply()

    var chimeEnabled: Boolean
        get() = sp.getBoolean("chime_enabled", true)
        set(v) = sp.edit().putBoolean("chime_enabled", v).apply()

    var calendarEnabled: Boolean
        get() = sp.getBoolean("calendar_enabled", true)
        set(v) = sp.edit().putBoolean("calendar_enabled", v).apply()

    var calendarLeadMinutes: Int
        get() = sp.getInt("calendar_lead", 5)
        set(v) = sp.edit().putInt("calendar_lead", v).apply()

    var newsEnabled: Boolean
        get() = sp.getBoolean("news_enabled", false)
        set(v) = sp.edit().putBoolean("news_enabled", v).apply()

    /** ニュースを読む時刻（時）。カンマ区切り */
    var newsHours: String
        get() = sp.getString("news_hours", DEFAULT_NEWS_HOURS) ?: DEFAULT_NEWS_HOURS
        set(v) = sp.edit().putString("news_hours", v).apply()

    var newsCount: Int
        get() = sp.getInt("news_count", 5)
        set(v) = sp.edit().putInt("news_count", v).apply()

    var newsUrl: String
        get() = sp.getString("news_url", DEFAULT_NEWS_URL) ?: DEFAULT_NEWS_URL
        set(v) = sp.edit().putString("news_url", v).apply()

    var newsOnlyWhenMusic: Boolean
        get() = sp.getBoolean("news_only_music", true)
        set(v) = sp.edit().putBoolean("news_only_music", v).apply()

    /** 送信者が分かる通知の言い回し。{app} {sender} {name} {body} を使える */
    var notificationTemplate: String
        get() = sp.getString("notification_template", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
        set(v) = sp.edit().putString("notification_template", v).apply()

    /** 送信者が分からない通知の言い回し */
    var notificationTemplatePlain: String
        get() = sp.getString("notification_template_plain", DEFAULT_TEMPLATE_PLAIN) ?: DEFAULT_TEMPLATE_PLAIN
        set(v) = sp.edit().putString("notification_template_plain", v).apply()

    /** 通知の読み上げをこの文字数で打ち切る。0 で無制限 */
    var notificationMaxChars: Int
        get() = sp.getInt("notification_max_chars", 120)
        set(v) = sp.edit().putInt("notification_max_chars", v).apply()

    /** マナーモード（消音・バイブ）のときは自動の読み上げをしない。手動のボタンは鳴る */
    var muteInSilentMode: Boolean
        get() = sp.getBoolean("mute_in_silent", true)
        set(v) = sp.edit().putBoolean("mute_in_silent", v).apply()

    var pauseMusic: Boolean
        get() = sp.getBoolean("pause_music", false)
        set(v) = sp.edit().putBoolean("pause_music", v).apply()

    /** 長い読み上げだけ音楽を一時停止する。短い読み上げは音量を下げる */
    var pauseMusicForLongSpeech: Boolean
        get() = sp.getBoolean("pause_music_for_long_speech", true)
        set(v) = sp.edit().putBoolean("pause_music_for_long_speech", v).apply()

    /** ニュース前のチャイム。Chime.key */
    var newsChime: String
        get() = sp.getString("news_chime", "two") ?: "two"
        set(v) = sp.edit().putString("news_chime", v).apply()

    /** チャイムの音量。0.0〜1.0 で MediaPlayer にそのまま渡す */
    var newsChimeVolume: Float
        get() = sp.getFloat("news_chime_volume", 0.5f)
        set(v) = sp.edit().putFloat("news_chime_volume", v).apply()

    var newsChimeUri: String?
        get() = sp.getString("news_chime_uri", null)
        set(v) = sp.edit().putString("news_chime_uri", v).apply()

    /** VOICEVOX 互換エンジンの URL。改行区切りで複数。空なら端末の TTS */
    var ttsUrls: String
        get() = sp.getString("tts_urls", "") ?: ""
        set(v) = sp.edit().putString("tts_urls", v).apply()

    var ttsSpeaker: Int
        get() = sp.getInt("tts_speaker", 3)
        set(v) = sp.edit().putInt("tts_speaker", v).apply()

    /** 選んだ話者の表示名。エンジンに繋がらないときも画面に出せるように持っておく */
    var ttsSpeakerName: String
        get() = sp.getString("tts_speaker_name", "") ?: ""
        set(v) = sp.edit().putString("tts_speaker_name", v).apply()

    /** VOICEVOX の volumeScale。1.0 が素の音量。1.8 を超えると歪む */
    var ttsVolume: Float
        get() = sp.getFloat("tts_volume", 1.5f)
        set(v) = sp.edit().putFloat("tts_volume", v).apply()

    var ttsSpeed: Float
        get() = sp.getFloat("tts_speed", 1.0f)
        set(v) = sp.edit().putFloat("tts_speed", v).apply()

    /** 読み上げ済みの予定。"eventId:beginMillis" の集合 */
    var announcedEvents: Set<String>
        get() = sp.getStringSet("announced_events", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("announced_events", v.toSet()).apply()

    companion object {
        const val DEFAULT_TEMPLATE = "{sender}から{app}です。{body}"
        const val DEFAULT_TEMPLATE_PLAIN = "{app}です。{body}"
        const val DEFAULT_NEWS_HOURS = "9,12,15,18"
        const val DEFAULT_NEWS_URL = "https://www.nhk.or.jp/rss/news/cat0.xml"
    }
}
