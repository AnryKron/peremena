package com.andrew.peremena

import android.app.Application
import com.andrew.peremena.alarm.AlarmScheduler
import com.andrew.peremena.alarm.Notifier
import com.andrew.peremena.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.createChannels(this)
        AlarmScheduler.reschedule(this)
    }
}
