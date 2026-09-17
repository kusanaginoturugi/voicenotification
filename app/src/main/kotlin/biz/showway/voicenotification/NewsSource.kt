package biz.showway.voicenotification

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

data class NewsItem(val title: String, val description: String)

object NewsSource {
    private const val TAG = "NewsSource"

    /** 要約テキストがこれより古ければ使わずに次の URL へ回す（要約側が止まっているとみなす） */
    private const val SUMMARY_MAX_AGE_MS = 2 * 60 * 60 * 1000L

    /**
     * URL の中身を読み上げ文にする。RSS / Atom なら先頭 count 件の見出しと概要、
     * それ以外（要約済みのプレーンテキストなど）はそのまま返す。失敗したら例外
     */
    fun fetchSpeech(url: String, count: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "VoiceNotification/0.1")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.use { it.readBytes() }
            val text = String(body, Charsets.UTF_8).trim()
            if (text.isEmpty()) error("empty body")
            val modified = conn.lastModified
            if (!text.startsWith("<") && modified > 0 &&
                System.currentTimeMillis() - modified > SUMMARY_MAX_AGE_MS
            ) error("stale summary")
            return if (text.startsWith("<")) toSpeech(parse(body.inputStream(), count)) else text
        } finally {
            conn.disconnect()
        }
    }

    /** 改行区切りの URL を上から順に試す。全滅なら最後の例外を投げる */
    fun fetchFirst(urls: String, count: Int): String {
        var last: Exception? = null
        for (u in urls.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            try {
                return fetchSpeech(u, count)
            } catch (e: Exception) {
                Log.w(TAG, "news fetch failed at $u: ${e.javaClass.simpleName} ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("no news url")
    }

    private fun parse(input: java.io.InputStream, count: Int): List<NewsItem> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val items = ArrayList<NewsItem>()
        var inItem = false
        var title = ""
        var desc = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && items.size < count) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item", "entry" -> { inItem = true; title = ""; desc = "" }
                    "title" -> if (inItem) title = parser.nextText()
                    "description", "summary" -> if (inItem) desc = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (inItem && (parser.name == "item" || parser.name == "entry")) {
                    inItem = false
                    if (title.isNotBlank()) items += NewsItem(clean(title), clean(desc))
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun clean(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun toSpeech(items: List<NewsItem>): String {
        if (items.isEmpty()) return "ニュースは取得できませんでした。"
        val sb = StringBuilder("ニュースです。")
        items.forEach { item ->
            sb.append(item.title).append("。")
            if (item.description.isNotEmpty()) sb.append(item.description).append("。")
        }
        sb.append("以上です。")
        return sb.toString()
    }
}
