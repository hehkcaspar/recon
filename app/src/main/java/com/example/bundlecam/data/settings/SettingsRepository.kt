package com.example.bundlecam.data.settings

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class StitchQuality(val label: String) {
    LOW("Low"),
    STANDARD("Standard"),
    HIGH("High"),
}

data class SettingsState(
    val rootUri: Uri? = null,
    val stitchQuality: StitchQuality = StitchQuality.STANDARD,
    val shutterSoundOn: Boolean = true,
)

private object Keys {
    val ROOT_URI = stringPreferencesKey("root_uri")
    val STITCH_QUALITY = stringPreferencesKey("stitch_quality")
    val SHUTTER_SOUND_ON = booleanPreferencesKey("shutter_sound_on")
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<SettingsState> = store.data
        .map { prefs ->
            SettingsState(
                rootUri = prefs[Keys.ROOT_URI]
                    ?.let { runCatching { it.toUri() }.getOrNull() },
                stitchQuality = prefs[Keys.STITCH_QUALITY]
                    ?.let { runCatching { StitchQuality.valueOf(it) }.getOrNull() }
                    ?: StitchQuality.STANDARD,
                shutterSoundOn = prefs[Keys.SHUTTER_SOUND_ON] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun setRootUri(uri: Uri) {
        store.edit { it[Keys.ROOT_URI] = uri.toString() }
    }

    suspend fun setStitchQuality(quality: StitchQuality) {
        store.edit { it[Keys.STITCH_QUALITY] = quality.name }
    }

    suspend fun setShutterSoundOn(on: Boolean) {
        store.edit { it[Keys.SHUTTER_SOUND_ON] = on }
    }
}
