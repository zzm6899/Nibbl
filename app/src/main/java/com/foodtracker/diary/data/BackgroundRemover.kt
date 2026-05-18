package com.foodtracker.diary.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackgroundRemover(private val context: Context) {
    private val segmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        SubjectSegmentation.getClient(options)
    }

    suspend fun removeToPngBytes(uri: Uri): ByteArray {
        SubjectSegmentationModuleInstaller.ensureInstalled(context, segmenter)
        val image = InputImage.fromFilePath(context, uri)
        val foreground = suspendCancellableCoroutine<Bitmap> { continuation ->
            segmenter.process(image)
                .addOnSuccessListener { result ->
                    val bitmap = result.foregroundBitmap
                    if (!continuation.isActive) {
                        bitmap?.recycle()
                    } else if (bitmap != null) {
                        continuation.resume(bitmap)
                    } else {
                        continuation.resumeWithException(IllegalStateException("No foreground subject found"))
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
        }
        return ByteArrayOutputStream().use { stream ->
            val compressed = foreground.compress(Bitmap.CompressFormat.PNG, 100, stream)
            foreground.recycle()
            if (!compressed) throw IllegalStateException("Could not encode foreground image")
            stream.toByteArray().also {
                if (it.isEmpty()) throw IllegalStateException("Encoded foreground image was empty")
            }
        }
    }
}
