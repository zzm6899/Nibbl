package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BackendDrinkReporter {
    suspend fun submit(shareHost: String, log: FoodLog, settings: AppSettings? = null) = withContext(Dispatchers.IO) {
        val apiToken = settings?.apiToken.orEmpty()
        if (apiToken.isBlank()) return@withContext

        runCatching {
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/nibbl/ingest"
            val boundary = "NibblBoundary${System.currentTimeMillis()}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Authorization", "Bearer $apiToken")
            }

            DataOutputStream(connection.outputStream).use { output ->
                output.writeMultipartField(boundary, "payload", log.toBackendJson(settings).toString())
                File(log.imagePath).takeIf { it.isFile }?.let { output.writeMultipartFile(boundary, "cutout", it) }
                File(log.originalImagePath).takeIf { it.isFile && it.absolutePath != log.imagePath }?.let {
                    output.writeMultipartFile(boundary, "original", it)
                }
                output.writeBytes("--$boundary--\r\n")
            }
            try {
                connection.inputStream?.close()
            } catch (_: Exception) {
                connection.errorStream?.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun FoodLog.toBackendJson(settings: AppSettings?): JSONObject =
    JSONObject()
        .put("timestamp", timestamp)
        .put("title", title)
        .put("category", category.id)
        .put("caffeineMg", caffeineMg ?: JSONObject.NULL)
        .put("cafe", cafe)
        .put("locationName", locationName)
        .put("latitude", latitude ?: JSONObject.NULL)
        .put("longitude", longitude ?: JSONObject.NULL)
        .put("friendNames", JSONArray(friendNames))
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

private fun DataOutputStream.writeMultipartFile(boundary: String, name: String, file: File) {
    val contentType = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    writeBytes("--$boundary\r\n")
    writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n")
    writeBytes("Content-Type: $contentType\r\n\r\n")
    file.inputStream().use { input -> input.copyTo(this) }
    writeBytes("\r\n")
}
