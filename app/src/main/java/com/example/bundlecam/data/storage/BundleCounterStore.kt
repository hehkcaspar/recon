package com.example.bundlecam.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import java.time.ZoneId

private val Context.counterDataStore: DataStore<Preferences> by preferencesDataStore(name = "bundle_counter")

private object Keys {
    val LAST_DATE = stringPreferencesKey("last_date")
    val LAST_COUNTER = intPreferencesKey("last_counter")
}

class BundleCounterStore(context: Context) {
    private val store = context.applicationContext.counterDataStore

    suspend fun allocate(): String {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        var allocated = ""
        store.edit { prefs ->
            val storedDate = prefs[Keys.LAST_DATE]
            val storedCounter = prefs[Keys.LAST_COUNTER] ?: 0
            val nextCounter = if (storedDate != today) 1 else storedCounter + 1
            prefs[Keys.LAST_DATE] = today
            prefs[Keys.LAST_COUNTER] = nextCounter
            allocated = StorageLayout.bundleId(today, nextCounter)
        }
        return allocated
    }
}
