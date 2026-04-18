package com.example.bundlecam.data.settings

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val saveIndividualPhotos: Boolean = true,
    val saveStitchedImage: Boolean = true,
    val deleteDelaySeconds: Int = DEFAULT_DELETE_DELAY_SECONDS,
    val deleteConfirmEnabled: Boolean = true,
)

const val DEFAULT_DELETE_DELAY_SECONDS: Int = 5
const val MIN_DELETE_DELAY_SECONDS: Int = 0
const val MAX_DELETE_DELAY_SECONDS: Int = 10

private object Keys {
    val ROOT_URI = stringPreferencesKey("root_uri")
    val STITCH_QUALITY = stringPreferencesKey("stitch_quality")
    val SHUTTER_SOUND_ON = booleanPreferencesKey("shutter_sound_on")
    val SAVE_INDIVIDUAL_PHOTOS = booleanPreferencesKey("save_individual_photos")
    val SAVE_STITCHED_IMAGE = booleanPreferencesKey("save_stitched_image")
    val DELETE_DELAY_SECONDS = intPreferencesKey("delete_delay_seconds")
    val DELETE_CONFIRM_ENABLED = booleanPreferencesKey("delete_confirm_enabled")
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<SettingsState> = store.data
        .map { prefs ->
            val indiv = prefs[Keys.SAVE_INDIVIDUAL_PHOTOS] ?: true
            val stitch = prefs[Keys.SAVE_STITCHED_IMAGE] ?: true
            // Belt-and-suspenders: setters enforce ≥1-on, but a corrupt (false, false) on
            // disk (external edit, restore from backup) must never silently drop bundles.
            val bothOff = !indiv && !stitch
            SettingsState(
                rootUri = prefs[Keys.ROOT_URI]
                    ?.let { runCatching { it.toUri() }.getOrNull() },
                stitchQuality = prefs[Keys.STITCH_QUALITY]
                    ?.let { runCatching { StitchQuality.valueOf(it) }.getOrNull() }
                    ?: StitchQuality.STANDARD,
                shutterSoundOn = prefs[Keys.SHUTTER_SOUND_ON] ?: true,
                saveIndividualPhotos = if (bothOff) true else indiv,
                saveStitchedImage = if (bothOff) true else stitch,
                deleteDelaySeconds = (prefs[Keys.DELETE_DELAY_SECONDS] ?: DEFAULT_DELETE_DELAY_SECONDS)
                    .coerceIn(MIN_DELETE_DELAY_SECONDS, MAX_DELETE_DELAY_SECONDS),
                deleteConfirmEnabled = prefs[Keys.DELETE_CONFIRM_ENABLED] ?: true,
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

    suspend fun setSaveIndividualPhotos(on: Boolean) =
        setOutputFlag(self = Keys.SAVE_INDIVIDUAL_PHOTOS, peer = Keys.SAVE_STITCHED_IMAGE, on = on)

    suspend fun setSaveStitchedImage(on: Boolean) =
        setOutputFlag(self = Keys.SAVE_STITCHED_IMAGE, peer = Keys.SAVE_INDIVIDUAL_PHOTOS, on = on)

    suspend fun setDeleteDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_DELETE_DELAY_SECONDS, MAX_DELETE_DELAY_SECONDS)
        store.edit { it[Keys.DELETE_DELAY_SECONDS] = clamped }
    }

    suspend fun setDeleteConfirmEnabled(on: Boolean) {
        store.edit { it[Keys.DELETE_CONFIRM_ENABLED] = on }
    }

    // Peer read + self write live in one edit{} so DataStore serializes concurrent toggles
    // and the ≥1-on invariant cannot be violated by two simultaneous taps.
    private suspend fun setOutputFlag(
        self: Preferences.Key<Boolean>,
        peer: Preferences.Key<Boolean>,
        on: Boolean,
    ) {
        store.edit { prefs ->
            val peerOn = prefs[peer] ?: true
            prefs[self] = if (!on && !peerOn) true else on
        }
    }
}
