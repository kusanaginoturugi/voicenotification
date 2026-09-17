package biz.showway.voicenotification

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

data class NewsItem(val title: String, val description: String)

object NewsSource {
    /** RSS 2.0 / Atom の item を先頭から count 件返す。失敗したら例外 */
    fun fetch(url: String, count: Int): List<NewsItem> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "VoiceNotification/0.1")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { return parse(it, count) }
        } finally {
            conn.disconnect()
        }
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
