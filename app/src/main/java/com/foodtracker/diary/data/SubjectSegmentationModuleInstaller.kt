package com.foodtracker.diary.data

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SubjectSegmentationModuleInstaller {
    suspend fun ensureInstalled(context: Context, segmenter: SubjectSegmenter) {
        val moduleInstallClient = ModuleInstall.getClient(context.applicationContext)
        val availability = moduleInstallClient.areModulesAvailable(segmenter).awaitResult()
        if (availability.areModulesAvailable()) return

        moduleInstallClient.installModulesAndWait(segmenter)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) {
            continuation.resumeWithException(CancellationException("Google Play services module install task was cancelled"))
        }
    }
}

private suspend fun ModuleInstallClient.installModulesAndWait(segmenter: SubjectSegmenter) {
    suspendCancellableCoroutine<Unit> { continuation ->
        lateinit var listener: InstallStatusListener
        val completed = AtomicBoolean(false)

        fun unregisterListener() {
            unregisterListener(listener)
        }

        fun resumeOnce(error: Throwable? = null) {
            if (!completed.compareAndSet(false, true)) return
            unregisterListener()
            if (!continuation.isActive) return

            if (error == null) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(error)
            }
        }

        listener = InstallStatusListener { update ->
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> resumeOnce()
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                    resumeOnce(CancellationException("Google Play services subject segmentation module install was cancelled"))
                }
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> {
                    val status = ModuleInstallStatusCodes.getStatusCodeString(update.errorCode)
                    resumeOnce(IllegalStateException("Google Play services subject segmentation module install failed: $status"))
                }
            }
        }

        val request = ModuleInstallRequest.newBuilder()
            .addApi(segmenter)
            .setListener(listener)
            .build()

        installModules(request)
            .addOnSuccessListener { response ->
                // installModules() only confirms that an install request started. When the
                // module is not already installed, the listener above reports completion.
                if (response.areModulesAlreadyInstalled() || response.sessionId == 0) {
                    resumeOnce()
                }
            }
            .addOnFailureListener { error -> resumeOnce(error) }
            .addOnCanceledListener {
                resumeOnce(CancellationException("Google Play services subject segmentation module install request was cancelled"))
            }

        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                unregisterListener()
            }
        }
    }
}
