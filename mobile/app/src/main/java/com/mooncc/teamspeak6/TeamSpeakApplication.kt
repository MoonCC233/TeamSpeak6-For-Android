package com.mooncc.teamspeak6

import android.app.Application
import com.mooncc.teamspeak6.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TeamSpeakApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }
}
