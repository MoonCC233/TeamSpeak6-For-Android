package com.mooncc.teamspeak9

import android.app.Application
import com.mooncc.teamspeak9.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TeamSpeakApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
    }
}
