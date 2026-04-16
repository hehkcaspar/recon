package com.example.bundlecam.pipeline

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit

private const val TAG = "BundleCam/WorkScheduler"
private const val UNIQUE_NAME_PREFIX = "bundle-work-"

data class BundleFailure(
    val workId: String,
    val bundleId: String,
    val message: String,
)

class WorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(bundleId: String) {
        val request = OneTimeWorkRequestBuilder<BundleWorker>()
            .setInputData(workDataOf(BundleWorker.KEY_BUNDLE_ID to bundleId))
            .addTag(BundleWorker.TAG_ALL)
            .addTag(tagFor(bundleId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            uniqueNameFor(bundleId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "Enqueued worker for bundleId=$bundleId uniqueName=${uniqueNameFor(bundleId)}")
    }

    fun observeFailures(): Flow<BundleFailure> = flow {
        val acknowledged = mutableSetOf<String>()
        var isFirstEmission = true
        workManager.getWorkInfosByTagFlow(BundleWorker.TAG_ALL).collect { infos ->
            val failed = infos.filter { it.state == WorkInfo.State.FAILED }
            if (isFirstEmission) {
                failed.forEach { acknowledged.add(it.id.toString()) }
                isFirstEmission = false
                return@collect
            }
            val fresh = failed.firstOrNull { it.id.toString() !in acknowledged } ?: return@collect
            acknowledged.add(fresh.id.toString())
            emit(
                BundleFailure(
                    workId = fresh.id.toString(),
                    bundleId = fresh.outputData.getString(BundleWorker.KEY_BUNDLE_ID) ?: "?",
                    message = fresh.outputData.getString(BundleWorker.KEY_ERROR)
                        ?: "Unknown error",
                ),
            )
        }
    }

    fun pruneCompleted() {
        workManager.pruneWork()
    }

    fun isTracked(bundleId: String): Boolean {
        val infos = workManager.getWorkInfosByTag(tagFor(bundleId)).get()
        return infos.any {
            it.state in setOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.SUCCEEDED,
            )
        }
    }

    private fun tagFor(bundleId: String) = "bundle_$bundleId"
    private fun uniqueNameFor(bundleId: String) = "$UNIQUE_NAME_PREFIX$bundleId"
}
