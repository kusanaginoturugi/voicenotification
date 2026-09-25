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

        val parsed = extractText(n) ?: return
        val (sender, body) = parsed
        if (body.isBlank()) return

        val key = "${sbn.packageName}|$sender|$body"
        val now = SystemClock.elapsedRealtime()
        recent.entries.removeAll { now - it.value > DEDUPE_MS }
        if (recent.containsKey(key)) return
        recent[key] = now

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val trimmed = Speech.truncate(Speech.notice(Speech.sanitize(body)), prefs.notificationMaxChars)
        if (trimmed.isBlank()) return
        val template =
            if (sender.isNotBlank()) prefs.notificationTemplate else prefs.notificationTemplatePlain
        val speech = Speech.compose(
            template,
            app = Speech.appReading(sbn.packageName, appName),
            sender = sender,
            body = trimmed,
        )
        Speaker.speak(this, speech, prefs.pauseMusic, prefs.pauseMusicForLongSpeech)
    }

    /** (送信者, 本文)。送信者が分からなければ空文字 */
    private fun extractText(n: Notification): Pair<String, String>? {
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        // MessagingStyle (LINE など) は最新メッセージだけ読む
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).lastOrNull()
            if (last != null) {
                val sender = (last.senderPerson?.name?.toString() ?: title).trim()
                val text = last.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) return sender to text
            }
        }

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        return "" to listOf(title, text).filter { it.isNotEmpty() }.joinToString("。")
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
