package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BackendBackupClient {
    suspend fun backupAll(shareHost: String, logs: List<FoodLog>, settings: AppSettings): Int = withContext(Dispatchers.IO) {
        if (settings.apiToken.isBlank()) return@withContext 0
        val reporter = BackendDrinkReporter()
        logs.forEach { reporter.submit(shareHost, it, settings) }
        logs.size
    }

    suspend fun restore(
        repository: FoodLogRepository,
        settings: AppSettings,
    ): Int = withContext(Dispatchers.IO) {
        if (settings.apiToken.isBlank()) return@withContext 0
        for (host in ShareLinkTokenHelper.apiHostsFor(settings.shareHost)) {
            val restored = runCatching { restoreFromHost(host, repository, settings) }.getOrNull()
            if (restored != null) return@withContext restored
        }
        0
    }

    private suspend fun restoreFromHost(host: String, repository: FoodLogRepository, settings: AppSettings): Int {
        val endpoint = "$host/api/nibbl/logs"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
        }
        return try {
            if (connection.responseCode !in 200..299) return 0
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(body).optJSONArray("logs") ?: JSONArray()
            var count = 0
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() } ?: continue
                val imagePath = repository.saveBytes(downloadBytes(resolveUrl(host, imageUrl)), ".webp")
                val originalPath = json.optString("originalImageUrl").takeIf { it.isNotBlank() }?.let {
                    runCatching { repository.saveBytes(downloadBytes(resolveUrl(host, it)), ".webp") }.getOrNull()
                } ?: imagePath
                repository.save(json.toFoodLog(imagePath, originalPath))
                count += 1
            }
            count
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toFoodLog(imagePath: String, originalPath: String): FoodLog =
        FoodLog(
            id = optString("id").ifBlank { "${optLong("timestamp")}-${imagePath.hashCode()}" },
            timestamp = optLong("timestamp", System.currentTimeMillis()),
            imagePath = imagePath,
            originalImagePath = originalPath,
            title = optString("title").ifBlank { "Food + drink" },
            category = parseCategory(optString("category")),
            caffeineMg = if (isNull("caffeineMg")) null else optInt("caffeineMg"),
            cafe = optString("cafe"),
            locationName = optString("locationName"),
            latitude = if (isNull("latitude")) null else optDouble("latitude"),
            longitude = if (isNull("longitude")) null else optDouble("longitude"),
            friendNames = optJSONArray("friendNames")?.let { array ->
                List(array.length()) { index -> array.optString(index).trim() }.filter { it.isNotBlank() }
            }.orEmpty(),
            sticker = optString("sticker"),
            calories = if (isNull("calories")) null else optInt("calories"),
            priceCents = if (isNull("priceCents")) null else optInt("priceCents"),
            rating = if (isNull("rating")) null else optInt("rating").coerceIn(1, 5),
            orderDetails = optString("orderDetails"),
            isWishlist = optBoolean("isWishlist", false),
            reaction = optString("reaction"),
            favorite = optBoolean("favorite", false),
        )

    private fun parseCategory(raw: String): DrinkCategory =
        DrinkCategory.find(raw) ?: raw.takeIf { it.isNotBlank() }?.let { DrinkCategory.custom(it, 0xFFD8EFF1.toInt()) } ?: DrinkCategory.Drink

    private fun resolveUrl(host: String, value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else "${host.trimEnd('/')}/${value.trimStart('/')}"

    private fun downloadBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
        }
        return try {
            if (connection.responseCode !in 200..299) error("Could not download backup image")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
