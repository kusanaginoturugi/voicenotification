package biz.showway.voicenotification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * プロセス内で 1 つだけ持つ再生キュー。チャイムと TTS を順番に処理する。
 * 再生中は音楽側にオーディオフォーカスを要求して、音量を下げるか一時停止させる。
 */
object Speaker {
    private const val TAG = "Speaker"

    private sealed class Job(val pause: Boolean) {
        class Text(val text: String, pause: Boolean) : Job(pause)
        class Chime(val uri: Uri, pause: Boolean) : Job(pause)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayDeque<Job>()
    private var current: Job? = null
    private var player: MediaPlayer? = null
    private var counter = 0

    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context) {
        if (tts != null) return
        app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        tts = TextToSpeech(app) { status ->
            handler.post {
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS init failed: $status")
                    return@post
                }
                val t = tts ?: return@post
                val r = t.setLanguage(Locale.JAPAN)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Japanese voice not available: $r")
                }
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished()
                    override fun onError(utteranceId: String?, errorCode: Int) = finished()
                })
                ready = true
                pump()
            }
        }
    }

    fun speak(context: Context, text: String, pause: Boolean = false) {
        if (text.isBlank()) return
        init(context)
        handler.post { queue.addLast(Job.Text(text, pause)); pump() }
    }

    fun chime(context: Context, uri: Uri, pause: Boolean = false) {
        init(context)
        handler.post { queue.addLast(Job.Chime(uri, pause)); pump() }
    }

    fun stop(context: Context) {
        init(context)
        handler.post {
            queue.clear()
            tts?.stop()
            releasePlayer()
            current = null
            abandonFocus()
        }
    }

    private fun pump() {
        if (!ready || current != null) return
        val job = queue.removeFirstOrNull()
        if (job == null) {
            // 連続で来たときにフォーカスを取り直さないよう、少し待ってから返す
            handler.postDelayed({ if (current == null && queue.isEmpty()) abandonFocus() }, 400)
            return
        }
        current = job
        if (focusRequest == null) requestFocus(job.pause)
        when (job) {
            is Job.Text -> {
                val r = tts?.speak(job.text, TextToSpeech.QUEUE_ADD, null, "u${counter++}")
                if (r != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "speak failed: $r")
                    finished()
                }
            }
            is Job.Chime -> playChime(job.uri)
        }
    }

    private fun playChime(uri: Uri) {
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(app, uri)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { finished() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "chime failed: $what/$extra")
                    finished(); true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "chime open failed: $uri", e)
            finished()
        }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun finished() {
        handler.post {
            if (current is Job.Chime) releasePlayer()
            current = null
            pump()
        }
    }

    private fun requestFocus(pause: Boolean) {
        val am = audio ?: return
        abandonFocus()
        val gain = if (pause) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        audio?.abandonAudioFocusRequest(req)
        focusRequest = null
    }
}
