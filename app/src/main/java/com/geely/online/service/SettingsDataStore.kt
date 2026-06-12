package com.geely.online.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

object AppSettingKeys {
    val LOGS_ENABLED = booleanPreferencesKey("logs_enabled")
    val DISPLAY_AS_APP_SOURCE_KEY = stringPreferencesKey("display_as_app_source_key")
    val DISPLAY_LIKE_INSTEAD_OF_PREVIOUS = booleanPreferencesKey("display_like_instead_of_previous")
    val LIKE_MODE = intPreferencesKey("like_mode")
}
