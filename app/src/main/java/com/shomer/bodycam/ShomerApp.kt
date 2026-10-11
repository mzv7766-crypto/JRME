package com.shomer.bodycam

import android.app.Application
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.service.Notifications

class ShomerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        RecordRepository.init(this)
        Notifications.createChannels(this)
        Thread { RecordRepository.enforceStorageLimit() }.start()
    }
}
