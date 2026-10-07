package biz.showway.voicenotification

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRouter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
    /** 日本語 TTS でおよそ 10 秒以上になる長さ。時報や短い通知はダッキングのままにする */
    private const val LONG_SPEECH_CHARS = 80

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
    private data class LocalSynthesis(val id: String, val job: Job, val file: File)
    private var localSynthesis: LocalSynthesis? = null
    private var playerCleanup: (() -> Unit)? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var playerBaseVolume = 1f
    private var forceSpeakerCap = false
    private var routedIds = emptySet<Int>()
    private var lastRouteLog = ""
    private var volumeDetails = ""
    private val volumeMonitor = object : Runnable {
        override fun run() {
            val mp = player ?: return
            updatePlaybackVolume(mp)
            handler.postDelayed(this, 100)
        }
    }
    private val volumeCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
            player?.let { updatePlaybackVolume(it) }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                capCurrentPlayback("becoming noisy")
            }
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id in routedIds }) capCurrentPlayback("output removed")
        }
    }

    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusPausesMusic = false
    private var mediaSession: MediaSession? = null

    /** 音声の再生開始からキュー終了までだけメディアボタンを受ける。 */
    private fun enableMediaButtons() {
        if (mediaSession != null) return
        mediaSession = MediaSession(app, "MimiyoriSpeech").apply {
            // 終了後にイヤホン操作でMimiyoriが再起動されないようにする。
            setMediaButtonReceiver(null)
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return false
                    if (event.keyCode !in setOf(
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_STOP,
                            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        )) return super.onMediaButtonEvent(intent)
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        Log.i(TAG, "media button: stop speech")
                        stop(app)
                    }
                    return true
                }

                override fun onPause() { stop(app) }
                override fun onStop() { stop(app) }
                override fun onPlay() { stop(app) }
                override fun onSkipToNext() { stop(app) }
                override fun onSkipToPrevious() { stop(app) }
            }, handler)
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build())
            isActive = true
        }
    }

    private fun disableMediaButtons() {
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
    }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context) {
        if (tts != null) return
        app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(noisyReceiver, filter)
        }
        audio?.registerAudioDeviceCallback(deviceCallback, handler)
        app.getSystemService(MediaRouter::class.java).addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO, volumeCallback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        tts = TextToSpeech(app) { status ->
            handler.post {
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS init failed: $status")
                    return@post
                }
                val t = tts ?: return@post
                val r = t.setLanguage(app.resources.configuration.locales[0] ?: Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Japanese voice not available: $r")
                }
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = localSynthesisFinished(utteranceId, true)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = localSynthesisFinished(utteranceId, false)
                    override fun onError(utteranceId: String?, errorCode: Int) = localSynthesisFinished(utteranceId, false)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = localSynthesisFinished(utteranceId, false)
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

    /** 端末 TTS もファイル化し、実際の出力先を確認できる MediaPlayer で再生する。 */
    private fun synthesizeLocal(text: String, job: Job) {
        try {
            val pending = LocalSynthesis("local-${counter++}", job, File.createTempFile("local-tts-", ".wav", app.cacheDir))
            localSynthesis = pending
            val result = tts?.synthesizeToFile(text, Bundle(), pending.file, pending.id)
            if (result != TextToSpeech.SUCCESS) localSynthesisFinished(pending.id, false)
        } catch (e: Exception) {
            Log.w(TAG, "local synthesis failed", e)
            localSynthesis?.file?.delete()
            localSynthesis = null
            finished(job)
        }
    }

    private fun localSynthesisFinished(id: String?, success: Boolean) {
        handler.post {
            val pending = localSynthesis ?: return@post
            if (pending.id != id || current !== pending.job) return@post
            localSynthesis = null
            if (success && pending.file.length() > 0) {
                Log.i(TAG, "local synthesis ready")
                playFile(pending.file, keep = false)
            } else {
                Log.w(TAG, "local synthesis failed or stopped")
                pending.file.delete()
                finished(pending.job)
            }
        }
    }

    fun speak(
        context: Context,
        raw: String,
        pause: Boolean = false,
        pauseForLongSpeech: Boolean = false,
    ) {
        val text = Speech.sanitize(context, raw)
        if (text.isBlank()) return
        init(context)
        val prefs = Prefs(context)
        val shouldPause = pause || (pauseForLongSpeech && text.length >= LONG_SPEECH_CHARS)
        if (!RemoteTts.enabled(prefs)) {
            handler.post { queue.addLast(Job.Text(text, shouldPause)); pump() }
            return
        }
        val jobs = RemoteTts.chunk(text).map { Job.Remote(it, shouldPause) }
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
            localSynthesis?.file?.delete()
            localSynthesis = null
            tts?.stop()
            releasePlayer()
            current = null
            disableMediaButtons()
            abandonFocus()
        }
    }

    private fun pump() {
        if (!ready || current != null) return
        val head = queue.firstOrNull()
        if (head is Job.Remote && !head.done) return  // 合成待ち。完了時にまた pump される
        val job = queue.removeFirstOrNull()
        if (job == null) {
            disableMediaButtons()
            // 連続で来たときにフォーカスを取り直さないよう、少し待ってから返す
            handler.postDelayed({ if (current == null && queue.isEmpty()) abandonFocus() }, 400)
            return
        }
        current = job
        if (focusRequest == null || (job.pause && !focusPausesMusic)) requestFocus(job.pause)
        when (job) {
            is Job.Text -> synthesizeLocal(job.text, job)
            is Job.Chime -> playChime(job.uri, job.volume)
            is Job.Remote -> {
                val f = job.file
                if (f != null) playFile(f, keep = RemoteTts.isCached(f))
                else {
                    Log.w(TAG, "remote TTS unavailable, falling back to local")
                    synthesizeLocal(job.text, job)
                }
            }
        }
    }

    private fun playFile(file: File, keep: Boolean) = play(
        baseVolume = 1f,
        cleanup = { if (!keep) file.delete() },
        source = { setDataSource(file.path) },
    )

    private fun playChime(uri: Uri, volume: Float) = play(
        baseVolume = volume,
        source = { setDataSource(app, uri) },
    )

    private fun play(baseVolume: Float, cleanup: () -> Unit = {}, source: MediaPlayer.() -> Unit) {
        releasePlayer()
        val job = current ?: run { cleanup(); return }
        playerCleanup = cleanup
        playerBaseVolume = baseVolume
        forceSpeakerCap = false
        routedIds = emptySet()
        lastRouteLog = ""
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            mp.source()
            // 再生開始前はルートを取得できない。外部出力が確認できるまで上限を適用。
            val initial = baseVolume * Prefs(app).builtInSpeakerVolume
            mp.setVolume(initial, initial)
            val listener = AudioRouting.OnRoutingChangedListener {
                if (player === mp) updatePlaybackVolume(mp)
            }
            routingListener = listener
            mp.addOnRoutingChangedListener(listener, handler)
            mp.setOnPreparedListener {
                if (player === mp) {
                    mp.start()
                    enableMediaButtons()
                    updatePlaybackVolume(mp)
                    handler.postDelayed(volumeMonitor, 100)
                }
            }
            mp.setOnCompletionListener { finished(job) }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "playback failed: $what/$extra")
                finished(job)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "open failed", e)
            finished(job)
        }
    }

    /** 接続一覧ではなく、このプレーヤーが現在音を出しているデバイスを調べる。 */
    private fun updatePlaybackVolume(mp: MediaPlayer) {
        if (player !== mp) return
        val routes = runCatching {
            if (Build.VERSION.SDK_INT >= 36) mp.routedDevices
            else listOfNotNull(mp.routedDevice)
        }.getOrDefault(emptyList())
        routedIds = routes.map { it.id }.toSet()
        // 不明・本体・複数出力に本体を含む場合は上限を維持する。
        val externalOnly = routes.isNotEmpty() && routes.all { it.type in externalOutputTypes }
        val limit = Prefs(app).builtInSpeakerVolume
        volumeDetails = ""
        val multiplier = when {
            externalOnly && !forceSpeakerCap -> 1f
            routes.size == 1 && routes[0].type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> speakerGain(limit)
            else -> limit
        }
        val level = playerBaseVolume * multiplier
        mp.setVolume(level, level)
        val state = "routes=${routes.map { it.type }} multiplier=$multiplier gain=$level guarded=$forceSpeakerCap $volumeDetails"
        if (state != lastRouteLog) {
            Log.i(TAG, "playback $state")
            lastRouteLog = state
        }
    }

    private fun speakerGain(limit: Float): Float {
        val am = audio ?: return limit
        return runCatching {
            val stream = attrs.volumeControlStream
            val index = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val currentDb = am.getStreamVolumeDb(stream, index, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val maximumDb = am.getStreamVolumeDb(stream, max, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            volumeDetails = "stream=$stream index=$index/$max db=$currentDb maxDb=$maximumDb ceiling=$limit"
            if (am.isStreamMute(stream)) 0f else SpeakerVolume.gain(limit, currentDb, maximumDb)
        }.getOrDefault(limit)
    }

    // 不明なデバイスを外部と決めつけない。内蔵スピーカー・受話口は含めない。
    private val externalOutputTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_IP,
    ) + if (Build.VERSION.SDK_INT >= 33) setOf(AudioDeviceInfo.TYPE_BLE_BROADCAST) else emptySet()

    /** 取り外し通知を受けた発話は、ルート情報がまだ古くても最後まで上限を維持。 */
    private fun capCurrentPlayback(reason: String) {
        val mp = player ?: return
        forceSpeakerCap = true
        val level = playerBaseVolume * Prefs(app).builtInSpeakerVolume
        mp.setVolume(level, level)
        Log.i(TAG, "playback capped: $reason gain=$level")
    }

    private fun releasePlayer() {
        handler.removeCallbacks(volumeMonitor)
        val mp = player
        player = null
        if (mp != null) {
            routingListener?.let { mp.removeOnRoutingChangedListener(it) }
            runCatching { mp.stop() }
            mp.release()
        }
        routingListener = null
        routedIds = emptySet()
        playerCleanup?.invoke()
        playerCleanup = null
    }

    private fun finished(job: Job? = current) {
        handler.post {
            if (current !== job) return@post
            releasePlayer()
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
        focusPausesMusic = pause
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        audio?.abandonAudioFocusRequest(req)
        focusRequest = null
        focusPausesMusic = false
    }
}
