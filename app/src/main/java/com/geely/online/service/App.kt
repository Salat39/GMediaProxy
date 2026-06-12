package com.geely.online.service

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        OnlineMusicSettingsProvider.start(settingsDataStore, applicationScope)
        applicationScope.launch {
            settingsDataStore.data
                .map { it[AppSettingKeys.LOGS_ENABLED] ?: false }
                .distinctUntilChanged()
                .collect { AppLogger.setEnabled(it) }
        }
        OneOsApiBootstrap.initialize(this)
    }
}
