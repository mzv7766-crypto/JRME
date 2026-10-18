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
        RecordRepository.onAdded = { r -> if (!com.shomer.bodycam.cloud.CompanyBackup.onRecordAdded(this, r)) DriveBackup.onRecordAdded(this, r) }
        // a manager's company plan is (re)confirmed by the server once both the company and the Play purchase are known
        Cloud.scope.launch {
            kotlinx.coroutines.flow.combine(com.shomer.bodycam.cloud.OrgStore.state, AccountStore.state) { org, acc ->
                if (org.isManager && acc.teamToken != null) acc.teamToken to acc.teamPurchase?.basePlan else null
            }.distinctUntilChanged().collect { t -> if (t != null) com.shomer.bodycam.cloud.OrgStore.verifyTeam(t.first, t.second) }
        }
        // company phones: retry recordings that were not uploaded yet (e.g. recorded before the plan / offline)
        Cloud.scope.launch {
            com.shomer.bodycam.cloud.OrgStore.state.map { it.activeMember && it.org?.policy?.cloudBackup != com.shomer.bodycam.cloud.Policy.CLOUD_OFF }
                .distinctUntilChanged().collect { on -> if (on) com.shomer.bodycam.cloud.CompanyBackup.start(this@ShomerApp) }
        }
        // re-check the company whenever the app comes back to the screen
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { com.shomer.bodycam.cloud.OrgStore.refresh() }
        })
        Thread { RecordRepository.enforceStorageLimit() }.start()
    }
}
