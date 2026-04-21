package com.example.bundlecam.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Recon/VoiceController"

sealed class VoiceRecordingResult {
    data class Success(val file: File, val durationMs: Long) : VoiceRecordingResult()
    data class Error(val message: String, val cause: Throwable? = null) : VoiceRecordingResult()
    object PermissionDenied : VoiceRecordingResult()
    object NotRecording : VoiceRecordingResult()
}

/**
 * Owns the `MediaRecorder` lifecycle for voice capture. Deliberately independent of
 * the CameraX stack — voice modality keeps the camera preview bound (so switching back
 * to photo is instant), but the mic is exclusively held by MediaRecorder during record.
 *
 * Config: AAC-LC, MPEG-4 container (.m4a), 48 kHz mono, ~96 kbps. The encoded stream is
 * the accepted lightweight voice-memo baseline; if product ever wants true waveform
 * fidelity, swap to `AudioRecord` + `MediaCodec` — the wrapper here would become a
 * thinner wrapper around those primitives.
 */
class VoiceController(context: Context) {
    private val appContext: Context = context.applicationContext

    // start/stop run on Dispatchers.IO; the ViewModel coroutine scope serializes them
    // under the BusyState machine, so no extra lock is needed around these fields.
    @Volatile
    private var recorder: MediaRecorder? = null

    @Volatile
    private var currentOutput: File? = null

    @Volatile
    private var startedAtElapsedRealtime: Long = 0L

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Start a recording into [outputFile]. Returns [VoiceRecordingResult.Success] with
     * a 0-duration placeholder (caller discards; real duration is reported by
     * [stopRecording]). [VoiceRecordingResult.PermissionDenied] is a distinct case so
     * the VM can trigger the runtime permission flow instead of surfacing a banner.
     */
    suspend fun startRecording(outputFile: File): VoiceRecordingResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "startRecording: RECORD_AUDIO not granted")
            return@withContext VoiceRecordingResult.PermissionDenied
        }
        if (recorder != null) {
            Log.w(TAG, "startRecording: already recording")
            return@withContext VoiceRecordingResult.Error("Already recording")
        }
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(48_000)
            mr.setAudioChannels(1)
            mr.setOutputFile(outputFile.absolutePath)
            mr.prepare()
            mr.start()
        }.onFailure { t ->
            Log.e(TAG, "MediaRecorder setup failed", t)
            runCatching { mr.release() }
            return@withContext VoiceRecordingResult.Error(
                t.message ?: "Recording setup failed",
                t,
            )
        }
        recorder = mr
        currentOutput = outputFile
        startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        Log.i(TAG, "Voice recording started → ${outputFile.absolutePath}")
        VoiceRecordingResult.Success(outputFile, 0L)
    }

    /**
     * Stop and release. Returns Success with the final duration computed from
     * SystemClock.elapsedRealtime — more reliable than reading the m4a since the moov
     * atom only flushes during release().
     */
    suspend fun stopRecording(): VoiceRecordingResult = withContext(Dispatchers.IO) {
        val mr = recorder ?: return@withContext VoiceRecordingResult.NotRecording
        val output = currentOutput ?: return@withContext VoiceRecordingResult.NotRecording
        val durationMs = SystemClock.elapsedRealtime() - startedAtElapsedRealtime
        val stopResult = runCatching { mr.stop() }
        runCatching { mr.release() }
        recorder = null
        currentOutput = null
        stopResult.onFailure { t ->
            // MediaRecorder throws on stop() if the recording was too short to flush.
            // Delete the resulting partial file and surface an error.
            Log.w(TAG, "MediaRecorder.stop() threw", t)
            runCatching { output.delete() }
            return@withContext VoiceRecordingResult.Error(
                t.message ?: "Recording too short or failed to finalize",
                t,
            )
        }
        Log.i(TAG, "Voice recording stopped durationMs=$durationMs")
        VoiceRecordingResult.Success(output, durationMs)
    }

    /** Best-effort fire-and-forget stop for LifecycleEventEffect(ON_PAUSE). */
    fun stopRecordingSync() {
        val mr = recorder ?: return
        runCatching { mr.stop() }
        runCatching { mr.release() }
        recorder = null
        currentOutput = null
    }
}
