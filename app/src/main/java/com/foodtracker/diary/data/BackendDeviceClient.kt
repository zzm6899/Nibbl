package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BackendDeviceClient {
    suspend fun ensureRegistered(repository: AppSettingsRepository, settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        if (settings.apiToken.isNotBlank()) return@withContext settings

        runCatching {
            val endpoint = "${ShareLinkTokenHelper.apiHostFor(settings.shareHost)}/api/nibbl/devices/register"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject()
                .put("ownerId", settings.ownerId)
                .put("ownerName", settings.displayName)
                .put("ownerTag", settings.username)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val token = JSONObject(body).optString("apiToken", "").trim()
            if (token.isBlank()) settings else repository.save(settings.copy(apiToken = token))
        }.getOrDefault(settings)
    }
}
