package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BackendDeviceClient {
    suspend fun ensureRegistered(repository: AppSettingsRepository, settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        if (settings.apiToken.isNotBlank()) return@withContext settings

        for (host in ShareLinkTokenHelper.apiHostsFor(settings.shareHost)) {
            val registered = runCatching {
                registerAt("$host/api/nibbl/devices/register", settings, includeOwnerId = true)
                    ?: registerAt("$host/api/nibbl/devices/register", settings, includeOwnerId = false)
            }.getOrNull()
            if (registered != null) return@withContext repository.save(registered)
        }
        settings
    }

    private fun registerAt(endpoint: String, settings: AppSettings, includeOwnerId: Boolean): AppSettings? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            val payload = JSONObject()
                .put("ownerName", settings.displayName)
                .put("ownerTag", settings.username)
                .apply {
                    if (includeOwnerId) put("ownerId", settings.ownerId)
                }
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode == HttpURLConnection.HTTP_CONFLICT) {
                return null
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val token = json.optString("apiToken", "").trim()
            val ownerId = json.optString("ownerId", settings.ownerId).trim().ifBlank { settings.ownerId }
            if (token.isBlank()) null else settings.copy(ownerId = ownerId, apiToken = token)
        } finally {
            connection.disconnect()
        }
    }
}
