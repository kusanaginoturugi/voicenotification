package biz.showway.voicenotification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationReader : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.serviceEnabled) return
        if (sbn.packageName !in prefs.packages) return
        if (!Speaker.allowedNow(this)) return
        if (sbn.isOngoing) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val body = extractText(n) ?: return
        if (body.isBlank()) return

        val key = "${sbn.packageName}|$body"
        val now = SystemClock.elapsedRealtime()
        recent.entries.removeAll { now - it.value > DEDUPE_MS }
        if (recent.containsKey(key)) return
        recent[key] = now

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        Speaker.speak(this, "$appName。$body", prefs.pauseMusic)
    }

    private fun extractText(n: Notification): String? {
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        // MessagingStyle (LINE など) は最新メッセージだけ読む
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).lastOrNull()
            if (last != null) {
                val sender = last.senderPerson?.name?.toString() ?: title
                val text = last.text?.toString()?.trim().orEmpty()
                return listOf(sender, text).filter { it.isNotEmpty() }.joinToString("。")
            }
        }

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        return listOf(title, text).filter { it.isNotEmpty() }.joinToString("。")
    }

    companion object {
        private const val DEDUPE_MS = 60_000L

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(context, NotificationReader::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
