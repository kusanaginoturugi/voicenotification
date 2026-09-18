package biz.showway.voicenotification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 常駐用のフォアグラウンドサービス。
 * アラームから受け取ったアクションを実行して Speaker に流す。
 */
class VoiceService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        Speaker.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        if (!prefs.serviceEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            Scheduler.ACTION_CHIME -> chime(prefs)
            Scheduler.ACTION_CALENDAR -> calendarScan(prefs)
            Scheduler.ACTION_NEWS -> news(prefs, force = false)
            ACTION_NEWS_NOW -> news(prefs, force = true)
            ACTION_SPEAK -> intent.getStringExtra(EXTRA_TEXT)?.let { Speaker.speak(this, it, prefs.pauseMusic) }
            ACTION_CHIME_PREVIEW -> Chime.uriFor(this, prefs)?.let { Speaker.chime(this, it, prefs.newsChimeVolume, prefs.pauseMusic) }
            ACTION_STOP -> {
                Speaker.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun chime(prefs: Prefs) {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 時刻の文は毎回同じなので、予定と分けて読む（同じ文なら合成結果が使い回される）
        val timeText = buildString {
            append(if (h < 12) "午前" else "午後")
            append(if (h % 12 == 0) 12 else h % 12)
            append("時です。")
        }
        Speaker.speak(this, timeText, prefs.pauseMusic)

        if (!prefs.calendarEnabled) return
        val now = System.currentTimeMillis()
        val events = CalendarSource.upcoming(this, now, now + 60 * 60_000L)
        if (events.isEmpty()) return
        val sb = StringBuilder("この後の予定は、")
        events.forEach { sb.append(CalendarSource.timeText(it.begin)).append("、").append(it.title).append("。") }
        Speaker.speak(this, sb.toString(), prefs.pauseMusic)
    }

    private fun calendarScan(prefs: Prefs) {
        val now = System.currentTimeMillis()
        val lead = prefs.calendarLeadMinutes.coerceAtLeast(1)
        // スキャン間隔ぶん余裕を持たせて、取りこぼしを防ぐ
        val events = CalendarSource.upcoming(this, now, now + (lead + 5) * 60_000L)
        if (events.isEmpty()) return
        val announced = prefs.announcedEvents.filter { it.substringAfter(':').toLongOrNull()?.let { b -> b > now - 86_400_000L } == true }.toMutableSet()
        val sb = StringBuilder()
        events.forEach { e ->
            if (e.key in announced) return@forEach
            announced += e.key
            val minutes = ((e.begin - now) / 60_000L).coerceAtLeast(0)
            sb.append(if (minutes == 0L) "まもなく、" else "あと${minutes}分で、")
                .append(e.title).append("。")
        }
        prefs.announcedEvents = announced
        if (sb.isNotEmpty()) Speaker.speak(this, sb.toString(), prefs.pauseMusic)
    }

    private fun news(prefs: Prefs, force: Boolean) {
        if (!force && prefs.newsOnlyWhenMusic) {
            val am = getSystemService(AudioManager::class.java)
            if (!am.isMusicActive) return
        }
        val url = prefs.newsUrl
        val count = prefs.newsCount.coerceIn(1, 20)
        worker.execute {
            val text = try {
                NewsSource.fetchFirst(url, count)
            } catch (e: Exception) {
                Log.w(TAG, "news fetch failed", e)
                if (force) "ニュースの取得に失敗しました。" else return@execute
            }
            Chime.uriFor(this, prefs)?.let { Speaker.chime(this, it, prefs.newsChimeVolume, prefs.pauseMusic) }
            Speaker.speak(this, text, prefs.pauseMusic)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("読み上げ待機中")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_SPEAK = "biz.showway.voicenotification.SPEAK"
        const val ACTION_NEWS_NOW = "biz.showway.voicenotification.NEWS_NOW"
        const val ACTION_STOP = "biz.showway.voicenotification.STOP"
        const val ACTION_CHIME_PREVIEW = "biz.showway.voicenotification.CHIME_PREVIEW"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, action: String?, text: String? = null) {
            val i = Intent(context, VoiceService::class.java).setAction(action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))
        }
    }
}
