package biz.showway.voicenotification

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "読み上げサービス", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "voice_service"
    }
}
