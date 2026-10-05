package com.soundsync.app

import android.app.Application
import com.soundsync.app.data.local.SoundSyncDatabase
import com.soundsync.app.data.preferences.UserPreferences
import com.soundsync.app.service.SyncWorker

class SoundSyncApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SoundSyncDatabase.getInstance(this)

        val prefs = UserPreferences(this)
        if (prefs.autoSyncEnabled) {
            SyncWorker.schedulePeriodicSync(this, prefs.wifiOnly)
        }
    }
}
