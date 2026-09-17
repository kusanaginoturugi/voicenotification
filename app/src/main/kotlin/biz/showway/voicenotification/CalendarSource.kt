package biz.showway.voicenotification

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar

data class Event(val id: Long, val title: String, val begin: Long, val allDay: Boolean) {
    val key get() = "$id:$begin"
}

object CalendarSource {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** from < begin <= to の予定（終日は除く）を開始時刻順で返す */
    fun upcoming(context: Context, from: Long, to: Long): List<Event> {
        if (!hasPermission(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
            it.build()
        }
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = ArrayList<Event>()
        context.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val begin = c.getLong(2)
                if (begin <= from || begin > to) continue
                out += Event(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "予定" },
                    begin = begin,
                    allDay = c.getInt(3) != 0,
                )
            }
        }
        return out.filter { !it.allDay }
    }

    fun timeText(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return if (m == 0) "${h}時" else "${h}時${m}分"
    }
}
