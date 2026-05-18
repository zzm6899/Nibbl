package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackendDrinkReporter {
    suspend fun submit(shareHost: String, log: FoodLog) = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/collections/logs/records"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = log.toBackendJson().toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(payload) }
            try {
                if (connection.responseCode !in 200..299) {
                    connection.errorStream?.close()
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun FoodLog.toBackendJson(): JSONObject =
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
