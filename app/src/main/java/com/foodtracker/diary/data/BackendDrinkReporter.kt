package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

class BackendDrinkReporter {
    suspend fun submit(shareHost: String, log: FoodLog, settings: AppSettings? = null) = withContext(Dispatchers.IO) {
        val apiToken = settings?.apiToken.orEmpty()
        if (apiToken.isBlank()) return@withContext

        for (host in ShareLinkTokenHelper.apiHostsFor(shareHost)) {
            val sent = runCatching {
                val endpoint = "$host/api/nibbl/ingest"
                val boundary = "NibblBoundary${System.currentTimeMillis()}"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Authorization", "Bearer $apiToken")
                }

                try {
                    DataOutputStream(connection.outputStream).use { output ->
                        output.writeMultipartField(boundary, "payload", log.toBackendJson(settings).toString())
                        File(log.imagePath).takeIf { it.isFile }?.let {
                            output.writeMultipartImage(boundary, "cutout", it, maxDimension = 1280, quality = 82)
                        }
                        File(log.originalImagePath).takeIf { it.isFile && it.absolutePath != log.imagePath }?.let {
                            output.writeMultipartImage(boundary, "original", it, maxDimension = 1600, quality = 78)
                        }
                        output.writeBytes("--$boundary--\r\n")
                    }
                    val code = connection.responseCode
                    try {
                        connection.inputStream?.close()
                    } catch (_: Exception) {
                        connection.errorStream?.close()
                    }
                    code in 200..299
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (sent) return@withContext
        }
    }
}

private fun FoodLog.toBackendJson(settings: AppSettings?): JSONObject =
    JSONObject()
        .put("clientLogId", id)
        .put("timestamp", timestamp)
        .put("logDate", Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        .put("title", title)
        .put("category", category.id)
        .put("caffeineMg", caffeineMg ?: JSONObject.NULL)
        .put("cafe", cafe)
        .put("locationName", locationName)
        .put("latitude", latitude ?: JSONObject.NULL)
        .put("longitude", longitude ?: JSONObject.NULL)
        .put("friendNames", JSONArray(friendNames))
        .put("sticker", sticker)
        .put("ownerId", settings?.ownerId ?: "")
        .put("ownerName", settings?.displayName ?: "")
        .put("ownerTag", settings?.username?.ifBlank { settings.displayName.toFriendTag() } ?: "")

private fun String.toFriendTag(): String = toFriendInviteCode()

private fun DataOutputStream.writeMultipartField(boundary: String, name: String, value: String) {
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
    writeBytes("Content-Type: application/json; charset=utf-8\r\n\r\n")
    write(value.toByteArray(Charsets.UTF_8))
    writeBytes("\r\n")
}

private fun DataOutputStream.writeMultipartImage(
    boundary: String,
    name: String,
    file: File,
    maxDimension: Int,
    quality: Int,
) {
    val optimized = file.optimizedUploadBytes(maxDimension, quality)
    val bytes = optimized ?: file.readBytes()
    val filename = if (optimized != null) "${file.nameWithoutExtension}.webp" else file.name
    val contentType = if (optimized != null) {
        "image/webp"
    } else {
        when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
    writeBytes("Content-Type: $contentType\r\n\r\n")
    write(bytes)
    writeBytes("\r\n")
}

private fun File.optimizedUploadBytes(maxDimension: Int, quality: Int): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (longestSide <= 0) return@runCatching null

    val sample = highestPowerOfTwoAtMost((longestSide / maxDimension).coerceAtLeast(1))
    val bitmap = BitmapFactory.decodeFile(
        absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return@runCatching null

    ByteArrayOutputStream().use { stream ->
        @Suppress("DEPRECATION")
        val ok = bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), stream)
        bitmap.recycle()
        if (!ok) return@runCatching null
        stream.toByteArray().takeIf { it.isNotEmpty() && it.size < length() }
    }
}.getOrNull()

private fun highestPowerOfTwoAtMost(value: Int): Int {
    var sample = 1
    while (sample * 2 <= value) sample *= 2
    return sample
}
