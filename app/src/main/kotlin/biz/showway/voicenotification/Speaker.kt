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
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * プロセス内で 1 つだけ持つ再生キュー。チャイムと TTS を順番に処理する。
 * 再生中は音楽側にオーディオフォーカスを要求して、音量を下げるか一時停止させる。
 */
object Speaker {
    private const val TAG = "Speaker"

    private sealed class Job(val pause: Boolean) {
        class Text(val text: String, pause: Boolean) : Job(pause)
        class Chime(val uri: Uri, val volume: Float, pause: Boolean) : Job(pause)
        /** リモート合成。done になるまで再生は待つ。file が null なら端末 TTS に落とす */
        class Remote(val text: String, pause: Boolean) : Job(pause) {
            @Volatile var file: File? = null
            @Volatile var done = false
        }
    }

    private val synth = Executors.newSingleThreadExecutor()

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

    /** 自動の読み上げをしてよいか。マナーモードなら false */
    fun allowedNow(context: Context): Boolean {
        val prefs = Prefs(context)
        if (!prefs.muteInSilentMode) return true
        val am = context.getSystemService(AudioManager::class.java)
        val ok = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
        if (!ok) Log.i(TAG, "skipped: ringer mode ${am.ringerMode}")
        return ok
    }

    fun speak(context: Context, text: String, pause: Boolean = false) {
        if (text.isBlank()) return
        init(context)
        val prefs = Prefs(context)
        if (!RemoteTts.enabled(prefs)) {
            handler.post { queue.addLast(Job.Text(text, pause)); pump() }
            return
        }
        val jobs = RemoteTts.chunk(text).map { Job.Remote(it, pause) }
        handler.post { queue.addAll(jobs); pump() }
        jobs.forEach { job ->
            synth.execute {
                job.file = RemoteTts.synthesize(app, prefs, job.text)
                job.done = true
                handler.post { pump() }
            }
        }
    }

    fun chime(context: Context, uri: Uri, volume: Float = 1f, pause: Boolean = false) {
        init(context)
        handler.post { queue.addLast(Job.Chime(uri, volume.coerceIn(0f, 1f), pause)); pump() }
    }

    fun stop(context: Context) {
        init(context)
        handler.post {
            queue.forEach { job ->
                (job as? Job.Remote)?.file?.let { if (!RemoteTts.isCached(it)) it.delete() }
            }
            queue.clear()
            tts?.stop()
            releasePlayer()
            current = null
            abandonFocus()
        }
    }

    private fun pump() {
        if (!ready || current != null) return
        val head = queue.firstOrNull()
        if (head is Job.Remote && !head.done) return  // 合成待ち。完了時にまた pump される
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
            is Job.Chime -> playChime(job.uri, job.volume)
            is Job.Remote -> {
                val f = job.file
                if (f != null) playFile(f, keep = RemoteTts.isCached(f))
                else {
                    Log.w(TAG, "remote TTS unavailable, falling back to local")
                    val r = tts?.speak(job.text, TextToSpeech.QUEUE_ADD, null, "u${counter++}")
                    if (r != TextToSpeech.SUCCESS) finished()
                }
            }
        }
    }

    private fun playFile(file: File, keep: Boolean) {
        fun cleanup() { if (!keep) file.delete() }
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.path)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { cleanup(); finished() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback failed: $what/$extra")
                    cleanup(); finished(); true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "open failed: $file", e)
            cleanup(); finished()
        }
    }

    private fun playChime(uri: Uri, volume: Float) {
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(app, uri)
                setVolume(volume, volume)
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
            if (current is Job.Chime || current is Job.Remote) releasePlayer()
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
