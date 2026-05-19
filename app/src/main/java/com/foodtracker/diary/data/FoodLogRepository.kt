package com.foodtracker.diary.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class FoodLogRepository(private val context: Context) {
    private val entriesDir = File(context.filesDir, "entries").apply { mkdirs() }
    private val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
    private val storeFile = File(entriesDir, "food_logs.json")
    private val mutex = Mutex()

    suspend fun logs(): List<FoodLog> = withContext(Dispatchers.IO) {
        mutex.withLock { readLogs() }
    }

    suspend fun save(log: FoodLog) = withContext(Dispatchers.IO) {
        mutex.withLock { upsertInternal(log) }
    }

    suspend fun update(log: FoodLog) = save(log)

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readLogs()
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return@withLock false

            writeLogs(next)
            (current - next.toSet()).forEach { it.deleteImagesIfUnused(next) }
            true
        }
    }

    suspend fun find(id: String): FoodLog? = withContext(Dispatchers.IO) {
        mutex.withLock { readLogs().firstOrNull { it.id == id } }
    }

    suspend fun saveBytes(bytes: ByteArray, suffix: String): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Cannot save an empty image" }
        entriesDir.mkdirs()
        mutex.withLock {
            val safeSuffix = suffix.toSafeImageSuffix()
            val file = uniqueEntryFile(safeSuffix)
            val temp = File(entriesDir, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) temp.deleteOnExit()
            }
            file.absolutePath
        }
    }

    fun newCameraUri(): Uri {
        cameraDir.mkdirs()
        val file = File(cameraDir, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun logsForDate(logs: List<FoodLog>, date: LocalDate): List<FoodLog> {
        val zone = ZoneId.systemDefault()
        return logs.filter {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == date
        }.sortedBy { it.timestamp }
    }

    fun logsForMonth(logs: List<FoodLog>, month: java.time.YearMonth): List<FoodLog> {
        val zone = ZoneId.systemDefault()
        return logs.filter {
            java.time.YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month
        }.sortedBy { it.timestamp }
    }

    fun daySummary(logs: List<FoodLog>, date: LocalDate): FoodLogDaySummary {
        val dayLogs = logsForDate(logs, date)
        return FoodLogDaySummary(
            totalEntries = dayLogs.size,
            totalCaffeineMg = dayLogs.sumOf { it.caffeineMg ?: 0 },
            totalCalories = dayLogs.sumOf { it.calories ?: 0 },
            totalSpendCents = dayLogs.sumOf { it.priceCents ?: 0 },
            categoryCounts = dayLogs.groupingBy { it.category }.eachCount(),
            cafeCount = dayLogs.map { it.cafe.trim() }.filter { it.isNotEmpty() }.distinct().size,
            friendCount = dayLogs.flatMap { it.friendNames }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().size,
            favoriteCount = dayLogs.count { it.favorite },
            wishlistCount = dayLogs.count { it.isWishlist },
        )
    }

    private fun readLogs(): List<FoodLog> {
        if (!storeFile.exists()) return emptyList()
        val raw = runCatching { storeFile.readText() }.getOrDefault("")
        if (raw.isBlank()) return emptyList()

        val array = runCatching { raw.toFoodLogArray() }
            .onFailure { runCatching { storeFile.copyTo(corruptBackupFile(), overwrite = true) } }
            .getOrDefault(JSONArray())

        return List(array.length()) { index -> array.optJSONObject(index)?.toFoodLogOrNull() }
            .filterNotNull()
            .distinctBy { it.id }
            .sortedBy { it.timestamp }
    }

    private fun upsertInternal(log: FoodLog) {
        val current = readLogs()
        val next = (current.filterNot { it.id == log.id } + log).sortedBy { it.timestamp }
        writeLogs(next)
    }

    private fun writeLogs(logs: List<FoodLog>) {
        entriesDir.mkdirs()
        val temp = File(entriesDir, "${storeFile.name}.tmp")
        temp.writeText(JSONArray(logs.map { it.toJson() }).toString(2))
        if (!temp.renameTo(storeFile)) {
            temp.copyTo(storeFile, overwrite = true)
            if (!temp.delete()) temp.deleteOnExit()
        }
    }

    private fun uniqueEntryFile(suffix: String): File {
        var file: File
        do {
            file = File(entriesDir, "${UUID.randomUUID()}$suffix")
        } while (file.exists())
        return file
    }

    private fun corruptBackupFile(): File {
        val stamp = System.currentTimeMillis()
        return File(entriesDir, "food_logs.corrupt-$stamp.json")
    }

    private fun FoodLog.deleteImagesIfUnused(remaining: List<FoodLog>) {
        listOf(imagePath, originalImagePath)
            .filter { path -> path.isNotBlank() && remaining.none { it.imagePath == path || it.originalImagePath == path } }
            .map(::File)
            .filter { it.isFile && it.isInside(entriesDir) }
            .forEach { runCatching { it.delete() } }
    }
}

private fun FoodLog.toJson() = JSONObject()
    .put("id", id)
    .put("timestamp", timestamp)
    .put("imagePath", imagePath)
    .put("originalImagePath", originalImagePath)
    .put("title", title)
    .put("category", category.id)
    .put("caffeineMg", caffeineMg ?: JSONObject.NULL)
    .put("cafe", cafe)
    .put("locationName", locationName)
    .put("latitude", latitude ?: JSONObject.NULL)
    .put("longitude", longitude ?: JSONObject.NULL)
    .put("friendNames", JSONArray(friendNames))
    .put("sticker", sticker)
    .put("calories", calories ?: JSONObject.NULL)
    .put("priceCents", priceCents ?: JSONObject.NULL)
    .put("rating", rating ?: JSONObject.NULL)
    .put("orderDetails", orderDetails)
    .put("isWishlist", isWishlist)
    .put("reaction", reaction)
    .put("favorite", favorite)

private fun String.toFoodLogArray(): JSONArray {
    val trimmed = trim()
    if (trimmed.startsWith("[")) return JSONArray(trimmed)
    if (trimmed.startsWith("{")) {
        val objectValue = JSONObject(trimmed)
        return objectValue.optJSONArray("logs")
            ?: objectValue.optJSONArray("entries")
            ?: if (objectValue.has("timestamp") || objectValue.has("imagePath")) JSONArray().put(objectValue) else null
            ?: JSONArray()
    }
    throw JSONException("Food log store is not JSON")
}

private fun JSONObject.toFoodLogOrNull(): FoodLog? = runCatching {
    val imagePath = optNonBlankString("imagePath")
        ?: optNonBlankString("processedImagePath")
        ?: optNonBlankString("photoPath")
        ?: optNonBlankString("uri")
        ?: ""
    val originalImagePath = optNonBlankString("originalImagePath")
        ?: optNonBlankString("originalPhotoPath")
        ?: imagePath

    FoodLog(
        id = optNonBlankString("id") ?: UUID.nameUUIDFromBytes(toString().toByteArray()).toString(),
        timestamp = optLongOrNull("timestamp")
            ?: optLongOrNull("createdAt")
            ?: optLongOrNull("date")
            ?: 0L,
        imagePath = imagePath,
        originalImagePath = originalImagePath,
        title = optNonBlankString("title") ?: optNonBlankString("name") ?: "",
        category = parseCategory(optString("category")),
        caffeineMg = optIntOrNull("caffeineMg") ?: optIntOrNull("caffeine"),
        cafe = optNonBlankString("cafe") ?: optNonBlankString("place") ?: "",
        locationName = optNonBlankString("locationName") ?: optNonBlankString("location") ?: "",
        latitude = optDoubleOrNull("latitude"),
        longitude = optDoubleOrNull("longitude"),
        friendNames = optStringArray("friendNames").ifEmpty { optStringArray("friends") },
        sticker = optNonBlankString("sticker") ?: "",
        calories = optIntOrNull("calories") ?: optIntOrNull("calorieCount"),
        priceCents = optIntOrNull("priceCents"),
        rating = optIntOrNull("rating")?.coerceIn(1, 5),
        orderDetails = optNonBlankString("orderDetails") ?: optNonBlankString("order") ?: "",
        isWishlist = optBoolean("isWishlist", false),
        reaction = optNonBlankString("reaction") ?: "",
        favorite = optBoolean("favorite", false),
    )
}.getOrNull()

private fun parseCategory(raw: String): DrinkCategory {
    val normalized = raw.trim()
    return DrinkCategory.find(normalized)
        ?: normalized.takeIf { it.isNotBlank() }?.let { DrinkCategory.custom(it, 0xFFD8EFF1.toInt()) }
        ?: DrinkCategory.Drink
}

private fun JSONObject.optNonBlankString(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else runCatching { getInt(name) }.getOrNull()

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name) || isNull(name)) null else runCatching { getDouble(name) }.getOrNull()

private fun JSONObject.optStringArray(name: String): List<String> =
    optJSONArray(name)?.let { array ->
        List(array.length()) { index -> array.optString(index).trim() }
            .filter { it.isNotEmpty() && it != "null" }
    }.orEmpty()

private fun String.toSafeImageSuffix(): String {
    val clean = trim().lowercase().let { if (it.startsWith(".")) it else ".$it" }
        .filter { it.isLetterOrDigit() || it == '.' }
    return when (clean) {
        ".jpg", ".jpeg", ".png", ".webp" -> clean
        else -> ".img"
    }
}

private fun File.isInside(parent: File): Boolean = try {
    canonicalPath.startsWith(parent.canonicalPath + File.separator)
} catch (_: IOException) {
    false
}
