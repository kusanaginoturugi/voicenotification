package biz.showway.voicenotification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {
    const val ACTION_CHIME = "biz.showway.voicenotification.CHIME"
    const val ACTION_CALENDAR = "biz.showway.voicenotification.CALENDAR"
    const val ACTION_NEWS = "biz.showway.voicenotification.NEWS"

    private const val CALENDAR_SCAN_MINUTES = 5L

    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        cancel(context, ACTION_CHIME)
        cancel(context, ACTION_CALENDAR)
        cancel(context, ACTION_NEWS)
        if (!prefs.serviceEnabled) return
        if (prefs.chimeEnabled) scheduleNext(context, ACTION_CHIME)
        if (prefs.calendarEnabled) scheduleNext(context, ACTION_CALENDAR)
        if (prefs.newsEnabled) scheduleNext(context, ACTION_NEWS)
    }

    fun scheduleNext(context: Context, action: String) {
        val prefs = Prefs(context)
        val at = when (action) {
            ACTION_CHIME -> nextHour()
            ACTION_CALENDAR -> System.currentTimeMillis() + CALENDAR_SCAN_MINUTES * 60_000
            ACTION_NEWS -> nextAtHours(parseHours(prefs.newsHours)) ?: return
            else -> return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, action)
        val exact = am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun canScheduleExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun cancel(context: Context, action: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, action))
    }

    private fun pending(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** "9, 12,15 18" → [9, 12, 15, 18]。0〜23 以外は捨てる */
    fun parseHours(text: String): List<Int> =
        text.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.filter { it in 0..23 }.distinct().sorted()

    /** hours のうち、今より後で一番近い時刻。今日の分が終わっていれば翌日の最初 */
    private fun nextAtHours(hours: List<Int>): Long? {
        if (hours.isEmpty()) return null
        val now = Calendar.getInstance()
        val at = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val h = hours.firstOrNull { at.apply { set(Calendar.HOUR_OF_DAY, it) }.after(now) }
        if (h == null) {
            at.set(Calendar.HOUR_OF_DAY, hours.first())
            at.add(Calendar.DAY_OF_MONTH, 1)
        }
        return at.timeInMillis
    }

    private fun nextHour(): Long = Calendar.getInstance().apply {
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.HOUR_OF_DAY, 1)
    }.timeInMillis
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Scheduler.scheduleNext(context, action)
        VoiceService.start(context, action)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).serviceEnabled) return
        Scheduler.reschedule(context)
        VoiceService.start(context, null)
    }
}
