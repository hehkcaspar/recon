package com.example.bundlecam.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val TAG = "Recon/Location"
private const val TTL_MS = 30_000L
private const val COALESCE_THRESHOLD_MS = TTL_MS / 2

private data class CacheEntry(val location: Location, val capturedAt: Long)

class LocationProvider(context: Context) {
    private val appContext: Context = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val cache = AtomicReference<CacheEntry?>(null)
    private val refreshMutex = Mutex()

    fun getCachedOrNull(): Location? {
        val entry = cache.get() ?: return null
        val age = System.currentTimeMillis() - entry.capturedAt
        return if (age < TTL_MS) entry.location else null
    }

    suspend fun refresh() {
        if (!hasPermission()) return
        if (isFreshEnough()) return
        refreshMutex.withLock {
            if (isFreshEnough()) return
            val loc = runCatching { fetchLocation() }
                .onFailure { Log.w(TAG, "Location fetch failed", it) }
                .getOrNull() ?: return
            cache.set(CacheEntry(loc, System.currentTimeMillis()))
        }
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isFreshEnough(): Boolean {
        val entry = cache.get() ?: return false
        return System.currentTimeMillis() - entry.capturedAt < COALESCE_THRESHOLD_MS
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(): Location? = suspendCancellableCoroutine { cont ->
        val cts = CancellationTokenSource()
        cont.invokeOnCancellation { cts.cancel() }
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
}
