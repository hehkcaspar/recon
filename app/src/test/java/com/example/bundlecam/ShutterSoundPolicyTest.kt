package com.example.bundlecam

import com.example.bundlecam.ui.capture.BusyState
import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.ShutterSoundPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterSoundPolicyTest {

    @Test
    fun photo_plays_shutter_click_only_when_sound_enabled_and_idle() {
        assertTrue(
            ShutterSoundPolicy.shouldPlayPhotoShutterClick(
                modality = Modality.PHOTO,
                shutterSoundOn = true,
                busy = BusyState.Idle,
            ),
        )
        assertFalse(
            ShutterSoundPolicy.shouldPlayPhotoShutterClick(
                modality = Modality.PHOTO,
                shutterSoundOn = false,
                busy = BusyState.Idle,
            ),
        )
        assertFalse(
            ShutterSoundPolicy.shouldPlayPhotoShutterClick(
                modality = Modality.PHOTO,
                shutterSoundOn = true,
                busy = BusyState.Capturing,
            ),
        )
    }

    @Test
    fun video_and_voice_never_play_photo_shutter_click() {
        for (modality in listOf(Modality.VIDEO, Modality.VOICE)) {
            for (busy in BusyState.entries) {
                assertFalse(
                    "Modality $modality with busy=$busy must never play photo shutter click",
                    ShutterSoundPolicy.shouldPlayPhotoShutterClick(
                        modality = modality,
                        shutterSoundOn = true,
                        busy = busy,
                    ),
                )
            }
        }
    }
}
