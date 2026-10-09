package com.crosspoint.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Single shared Preferences DataStore for the whole app. */
val Context.launcherDataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

fun settingsDataStore(context: Context): DataStore<Preferences> = context.launcherDataStore
