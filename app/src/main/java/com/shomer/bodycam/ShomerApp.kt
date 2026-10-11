package com.shomer.bodycam

import android.app.Application
import com.shomer.bodycam.cloud.AccountStore
import com.shomer.bodycam.cloud.Cloud
import com.shomer.bodycam.cloud.DriveBackup
import com.shomer.bodycam.cloud.Presence
import com.shomer.bodycam.data.RecordRepository
import com.shomer.bodycam.data.SettingsStore
import com.shomer.bodycam.service.Notifications
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ShomerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        RecordRepository.init(this)
        Notifications.createChannels(this)
        AccountStore.init(this)
        Presence.init(this)
        // personal Pro unlocks the Pro features (a company phone unlocks them through its policy)
        Cloud.scope.launch { AccountStore.state.map { it.personalPro }.distinctUntilChanged().collect { SettingsStore.setPro(it) } }
        RecordRepository.onAdded = { r -> DriveBackup.onRecordAdded(this, r) }
        Thread { RecordRepository.enforceStorageLimit() }.start()
    }
}
