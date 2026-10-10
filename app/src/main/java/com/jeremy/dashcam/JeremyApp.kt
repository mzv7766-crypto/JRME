package com.jeremy.dashcam

import android.app.Application
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.service.Notifications

class JeremyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        com.jeremy.dashcam.data.ProStore.init(this)
        EventRepository.init(this)
        EventRepository.onAdded = { r -> com.jeremy.dashcam.core.pro.DriveBackup.onRecordAdded(this, r) }
        Notifications.createChannels(this)
        Thread { EventRepository.enforceStorageLimit() }.start()
    }
}
