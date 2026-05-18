package com.foodtracker.diary.data

import au.z2hs.nibbl.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackendDrinkReporter {
    suspend fun submit(shareHost: String, log: FoodLog, settings: AppSettings? = null) = withContext(Dispatchers.IO) {
        val ingestKey = BuildConfig.NIBBL_INGEST_KEY
        if (ingestKey.isBlank()) return@withContext

        runCatching {
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/nibbl/ingest"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Nibbl-Key", ingestKey)
            }

            val payload = log.toBackendJson(settings).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
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
