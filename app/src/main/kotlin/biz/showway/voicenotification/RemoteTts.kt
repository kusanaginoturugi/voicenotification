package biz.showway.voicenotification

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * VOICEVOX 互換エンジンで合成する。URL は複数持てて、最初に応答したものを使う。
 * 全部失敗したら null を返し、呼び出し側が端末の TTS に落とす。
 */
object RemoteTts {
    private const val TAG = "RemoteTts"
    private const val CONNECT_TIMEOUT = 1500
    private const val READ_TIMEOUT = 30_000

    @Volatile private var lastGood: String? = null

    fun enabled(prefs: Prefs): Boolean = urls(prefs).isNotEmpty()

    fun urls(prefs: Prefs): List<String> =
        prefs.ttsUrls.lines().map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    /** 各 URL の /version を叩いて結果を返す。UI の接続テスト用 */
    fun probe(prefs: Prefs): List<Pair<String, String>> = urls(prefs).map { base ->
        base to try {
            val c = open("$base/version", "GET")
            val v = c.inputStream.bufferedReader().readText().trim('"', '\n')
            c.disconnect(); "OK $v"
        } catch (e: Exception) {
            "NG ${e.javaClass.simpleName}"
        }
    }

    /** 合成した WAV ファイル。失敗なら null */
    fun synthesize(context: Context, prefs: Prefs, text: String): File? {
        val list = urls(prefs)
        if (list.isEmpty()) return null
        val ordered = lastGood?.let { g -> listOf(g) + list.filter { it != g } } ?: list
        for (base in ordered) {
            try {
                val f = synthesizeAt(context, base, prefs.ttsSpeaker, prefs.ttsSpeed, text)
                lastGood = base
                return f
            } catch (e: Exception) {
                Log.w(TAG, "synthesis failed at $base: ${e.javaClass.simpleName} ${e.message}")
                if (lastGood == base) lastGood = null
            }
        }
        return null
    }

    private fun synthesizeAt(context: Context, base: String, speaker: Int, speed: Float, text: String): File {
        val q = URLEncoder.encode(text, "UTF-8")
        var c = open("$base/audio_query?speaker=$speaker&text=$q", "POST")
        c.setFixedLengthStreamingMode(0)
        c.outputStream.close()
        if (c.responseCode != 200) error("audio_query HTTP ${c.responseCode}")
        val query = JSONObject(c.inputStream.bufferedReader().readText())
        c.disconnect()
        query.put("speedScale", speed.toDouble())

        c = open("$base/synthesis?speaker=$speaker", "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "audio/wav")
        c.outputStream.use { it.write(query.toString().toByteArray()) }
        if (c.responseCode != 200) error("synthesis HTTP ${c.responseCode}")
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        val out = File.createTempFile("v", ".wav", dir)
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        c.disconnect()
        return out
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = method == "POST"
        }

    /** 長文を文単位に分けて、合成と再生をパイプラインできるようにする */
    fun chunk(text: String, max: Int = 80): List<String> {
        val parts = text.split(Regex("(?<=[。！？\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && sb.length + p.length > max) { out += sb.toString(); sb.clear() }
            sb.append(p)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
