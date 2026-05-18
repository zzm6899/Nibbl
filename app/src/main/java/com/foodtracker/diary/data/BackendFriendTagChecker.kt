package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ResolvedFriendTag(
    val displayName: String,
    val tag: String,
    val avatarUrl: String? = null,
)

object BackendFriendTagChecker {
    suspend fun isAvailable(shareHost: String, tag: String): Boolean = withContext(Dispatchers.IO) {
        val cleanTag = tag.toFriendInviteCode()
        if (cleanTag.length < 3) return@withContext false

        for (host in ShareLinkTokenHelper.apiHostsFor(shareHost)) {
            val available = runCatching {
            val encoded = URLEncoder.encode(cleanTag, Charsets.UTF_8.name())
            val endpoint = "$host/api/nibbl/friends/available?tag=$encoded"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body).optBoolean("available", true)
            }.getOrNull()
            if (available != null) return@withContext available
        }
        false
    }

    suspend fun resolve(shareHost: String, tagOrUrl: String): ResolvedFriendTag? = withContext(Dispatchers.IO) {
        val invite = ShareLinkTokenHelper.parseCrewInviteUrl(tagOrUrl)

        val cleanTag = invite?.code ?: tagOrUrl.toFriendInviteCode()
        if (cleanTag.length < 3) return@withContext null

        for (host in ShareLinkTokenHelper.apiHostsFor(shareHost)) {
            val resolved = runCatching {
            val encoded = URLEncoder.encode(cleanTag, Charsets.UTF_8.name())
            val endpoint = "$host/api/nibbl/friends/resolve?tag=$encoded"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (connection.responseCode !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                return@runCatching null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            ResolvedFriendTag(
                displayName = json.optString("displayName", invite?.displayName ?: cleanTag).trim().ifBlank { invite?.displayName ?: cleanTag },
                tag = json.optString("tag", cleanTag).toFriendInviteCode(),
                avatarUrl = json.optString("avatarUrl", "").trim().takeIf { it.isNotBlank() },
            )
            }.getOrNull()
            if (resolved != null) return@withContext resolved
        }
        null
    }

    suspend fun updateOwnerProfile(shareHost: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        if (settings.apiToken.isBlank() || settings.ownerId.isBlank()) return@withContext false

        for (host in ShareLinkTokenHelper.apiHostsFor(shareHost)) {
            val updated = runCatching {
            val endpoint = "$host/api/nibbl/profile"
            val avatarFile = settings.profileImagePath?.let(::File)?.takeIf { it.isFile }
            val boundary = "NibblProfile${System.currentTimeMillis()}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    if (avatarFile != null) "multipart/form-data; boundary=$boundary" else "application/json",
                )
                setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
            }

            val payload = JSONObject()
                .put("ownerId", settings.ownerId)
                .put("ownerName", settings.displayName)
                .put("ownerTag", settings.username)
            if (avatarFile != null) {
                DataOutputStream(connection.outputStream).use { output ->
                    output.writeMultipartField(boundary, "payload", payload.toString())
                    output.writeMultipartFile(boundary, "avatar", avatarFile)
                    output.writeBytes("--$boundary--\r\n")
                }
            } else {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            try {
                connection.inputStream?.close()
            } catch (_: Exception) {
                connection.errorStream?.close()
            } finally {
                connection.disconnect()
            }
            code in 200..299
            }.getOrDefault(false)
            if (updated) return@withContext true
        }
        false
    }
}

fun String.toFriendInviteCode(): String =
    trim().filter(Char::isLetterOrDigit).lowercase().take(10)

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
