package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

object BackendShareClient {
    suspend fun createDayShare(repository: AppSettingsRepository, settings: AppSettings, date: LocalDate): String? = withContext(Dispatchers.IO) {
        val registered = BackendDeviceClient.ensureRegistered(repository, settings)
        if (registered.apiToken.isBlank()) return@withContext null

        for (host in ShareLinkTokenHelper.apiHostsFor(registered.shareHost)) {
            val url = runCatching {
                createDayShareAt(host, registered, date)
            }.getOrNull()
            if (!url.isNullOrBlank()) return@withContext url
        }
        null
    }

    private fun createDayShareAt(host: String, registered: AppSettings, date: LocalDate): String? {
        val endpoint = "$host/api/nibbl/shares/day"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${registered.apiToken}")
        }
        return try {
            val payload = JSONObject()
                .put("date", date.toString())
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val path = JSONObject(body).optString("url", "")
            if (path.startsWith("/")) "${ShareLinkTokenHelper.normalizeShareHost(registered.shareHost)}$path" else path.takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }
}
