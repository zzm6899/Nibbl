package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object BackendFriendTagChecker {
    suspend fun isAvailable(shareHost: String, tag: String): Boolean = withContext(Dispatchers.IO) {
        val cleanTag = tag.toFriendInviteCode()
        if (cleanTag.length < 3) return@withContext false

        runCatching {
            val encoded = URLEncoder.encode(cleanTag, Charsets.UTF_8.name())
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/nibbl/friends/available?tag=$encoded"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body).optBoolean("available", true)
        }.getOrDefault(true)
    }

    suspend fun updateOwnerProfile(shareHost: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        if (settings.apiToken.isBlank() || settings.ownerId.isBlank()) return@withContext false

        runCatching {
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/nibbl/profile"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
            }

            val payload = JSONObject()
                .put("ownerId", settings.ownerId)
                .put("ownerName", settings.displayName)
                .put("ownerTag", settings.username)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
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
    }
}

fun String.toFriendInviteCode(): String =
    trim().filter(Char::isLetterOrDigit).lowercase().take(10)
