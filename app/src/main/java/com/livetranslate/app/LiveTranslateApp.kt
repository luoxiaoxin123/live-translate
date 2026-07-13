package com.livetranslate.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.UserSettingsRepository

class LiveTranslateApp : Application() {
    lateinit var settingsRepository: UserSettingsRepository
        private set
    lateinit var apiKeyStore: ApiKeyStore
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = UserSettingsRepository(this)
        apiKeyStore = ApiKeyStore(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_SUBTITLE,
            getString(R.string.notification_channel_subtitle),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SUBTITLE = "subtitle_session"
    }
}
