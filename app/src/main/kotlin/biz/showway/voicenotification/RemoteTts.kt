package biz.showway.voicenotification

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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

    /** この文字数までの短い文だけキャッシュする。ニュースのような一度きりの長文は溜めない */
    private const val CACHE_MAX_CHARS = 60
    private const val CACHE_MAX_FILES = 300
    const val CACHE_DIR_NAME = "tts-cache"

    @Volatile private var lastGood: String? = null

    /** キャッシュに入っているファイルか。再生後に消してよいかの判定に使う */
    fun isCached(file: File): Boolean = file.parentFile?.name == CACHE_DIR_NAME

    fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /** 話者・速度・音量・本文が同じなら同じ名前になる */
    private fun cacheFile(context: Context, prefs: Prefs, text: String): File {
        val seed = "${prefs.ttsSpeed}|${prefs.ttsVolume}|$text"
        val hash = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(cacheDir(context), "${prefs.ttsSpeaker}_$hash.wav")
    }

    fun clearCache(context: Context): Int {
        val files = cacheDir(context).listFiles().orEmpty()
        files.forEach { it.delete() }
        return files.size
    }

    /** 古いものから消して CACHE_MAX_FILES 個に収める */
    private fun pruneCache(context: Context) {
        val files = cacheDir(context).listFiles().orEmpty()
        if (files.size <= CACHE_MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(CACHE_MAX_FILES)
            .forEach { it.delete() }
    }

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

    data class SpeakerStyle(val id: Int, val label: String)

    /** エンジンの話者一覧。最初に応答した URL のものを返す。全滅なら空 */
    fun speakers(prefs: Prefs): List<SpeakerStyle> {
        for (base in urls(prefs)) {
            try {
                val c = open("$base/speakers", "GET")
                val body = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val out = ArrayList<SpeakerStyle>()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val sp = arr.getJSONObject(i)
                    val name = sp.getString("name")
                    val styles = sp.getJSONArray("styles")
                    for (j in 0 until styles.length()) {
                        val st = styles.getJSONObject(j)
                        out += SpeakerStyle(st.getInt("id"), "$name ${st.getString("name")}")
                    }
                }
                return out
            } catch (e: Exception) {
                Log.w(TAG, "speakers failed at $base: ${e.javaClass.simpleName}")
            }
        }
        return emptyList()
    }

    /**
     * 合成した WAV ファイル。失敗なら null。
     * 短い文はキャッシュから返す。キャッシュのファイルは再生後に消さないこと（[isCached]）
     */
    fun synthesize(context: Context, prefs: Prefs, text: String): File? {
        val list = urls(prefs)
        if (list.isEmpty()) return null
        val cacheable = text.length <= CACHE_MAX_CHARS
        val cached = if (cacheable) cacheFile(context, prefs, text) else null
        if (cached != null && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())   // LRU 用
            Log.i(TAG, "cache hit: ${cached.name}")
            return cached
        }
        val ordered = lastGood?.let { g -> listOf(g) + list.filter { it != g } } ?: list
        for (base in ordered) {
            try {
                val f = synthesizeAt(context, base, prefs.ttsSpeaker, prefs.ttsSpeed, prefs.ttsVolume, text)
                lastGood = base
                if (cached == null) return f
                return if (f.renameTo(cached)) {
                    pruneCache(context)
                    Log.i(TAG, "cached: ${cached.name}")
                    cached
                } else {
                    f
                }
            } catch (e: Exception) {
                Log.w(TAG, "synthesis failed at $base: ${e.javaClass.simpleName} ${e.message}")
                if (lastGood == base) lastGood = null
            }
        }
        return null
    }

    private fun synthesizeAt(
        context: Context, base: String, speaker: Int, speed: Float, volume: Float, text: String,
    ): File {
        val q = URLEncoder.encode(text, "UTF-8")
        var c = open("$base/audio_query?speaker=$speaker&text=$q", "POST")
        c.setFixedLengthStreamingMode(0)
        c.outputStream.close()
        if (c.responseCode != 200) error("audio_query HTTP ${c.responseCode}")
        val query = JSONObject(c.inputStream.bufferedReader().readText())
        c.disconnect()
        query.put("speedScale", speed.toDouble())
        query.put("volumeScale", volume.coerceIn(0.1f, 1.8f).toDouble())

        c = open("$base/synthesis?speaker=$speaker", "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "audio/wav")
        c.outputStream.use { it.write(query.toString().toByteArray()) }
        if (c.responseCode != 200) error("synthesis HTTP ${c.responseCode}")
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        val out = File.createTempFile("voice", ".wav", dir)
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
