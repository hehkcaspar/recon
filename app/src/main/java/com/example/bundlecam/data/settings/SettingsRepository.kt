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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bundlecam.data.camera.CameraMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    // Set of tutorial step IDs the user has dismissed. Empty = show all steps on next
    // launch. Phase F replaced the Boolean v1 field with a set-based scheme so adding
    // new tutorial steps in later releases doesn't force returning users through the
    // whole flow — only unseen steps appear.
    val seenTutorialSteps: Set<String> = emptySet(),
    // Live-applied camera hardware mode. Demoted from the top-bar EXT/ZSL toggle to a
    // Settings row in Phase C; behaves like shutterSoundOn — not frozen into manifests.
    val cameraMode: CameraMode = CameraMode.ZSL,
)

/** Ids of the v1 tutorial steps — used to seed the seen-set on upgrade from Phase C- era. */
val V1_TUTORIAL_STEP_IDS: Set<String> = setOf("commit", "discard", "deleteOne", "reorder", "divide")

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
    val SEEN_GESTURE_TUTORIAL = booleanPreferencesKey("seen_gesture_tutorial")
    val SEEN_TUTORIAL_STEPS = stringSetPreferencesKey("seen_tutorial_steps")
    val CAMERA_MODE = stringPreferencesKey("camera_mode")
    val DEVICE_ALIAS = stringPreferencesKey("device_alias")
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
                // Upgrade fallback: if the new set key is absent but the v1 boolean
                // was true, seed the seen-set with the v1 step IDs so returning users
                // don't re-see what they've already dismissed. Pure read-side — no
                // DataStore write is needed for the migration itself.
                seenTutorialSteps = prefs[Keys.SEEN_TUTORIAL_STEPS]
                    ?: if (prefs[Keys.SEEN_GESTURE_TUTORIAL] == true) V1_TUTORIAL_STEP_IDS
                    else emptySet(),
                cameraMode = prefs[Keys.CAMERA_MODE]
                    ?.let { runCatching { CameraMode.valueOf(it) }.getOrNull() }
                    ?: CameraMode.ZSL,
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

    /**
     * "Show tutorial again" resets the seen-set to empty so every step appears on
     * next launch. The v1 boolean key is also cleared to avoid phantom-seeding if a
     * future downgrade happens. Preserved as a single-arg API matching the existing
     * Settings row `TextButton("Show")` call site.
     */
    suspend fun setSeenGestureTutorial(seen: Boolean) {
        store.edit {
            if (seen) {
                // Mark "all currently-known steps as seen" is not a useful API —
                // callers pass `seen = true` only to persist that the tutorial was
                // dismissed at the end, which is what `setSeenTutorialSteps` handles.
                // Keep the boolean write for back-compat but don't rely on it.
                it[Keys.SEEN_GESTURE_TUTORIAL] = true
            } else {
                it.remove(Keys.SEEN_GESTURE_TUTORIAL)
                it.remove(Keys.SEEN_TUTORIAL_STEPS)
            }
        }
    }

    suspend fun setSeenTutorialSteps(steps: Set<String>) {
        store.edit { it[Keys.SEEN_TUTORIAL_STEPS] = steps }
    }

    suspend fun setCameraMode(mode: CameraMode) {
        store.edit { it[Keys.CAMERA_MODE] = mode.name }
    }

    /**
     * Returns the per-install device alias used as the LocalSend `alias` field, generating
     * one on first call. Format: `"Recon NNNN"` with NNNN a 4-digit random suffix that
     * lets a household with multiple Recon devices distinguish them at a glance. The
     * value persists across app launches; clearing app data resets it.
     */
    suspend fun getOrCreateDeviceAlias(): String {
        val existing = store.data.first()[Keys.DEVICE_ALIAS]
        if (existing != null) return existing
        val generated = "Recon ${(1000..9999).random()}"
        store.edit { it[Keys.DEVICE_ALIAS] = generated }
        return generated
    }

    suspend fun setDeviceAlias(alias: String) {
        store.edit { it[Keys.DEVICE_ALIAS] = alias }
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
