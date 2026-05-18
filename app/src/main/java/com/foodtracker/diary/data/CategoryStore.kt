package com.foodtracker.diary.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CategoryStore(context: Context) {
    private val storeDir = File(context.filesDir, "settings").apply { mkdirs() }
    private val storeFile = File(storeDir, "categories.json")
    private val mutex = Mutex()

    suspend fun categories(): List<DrinkCategory> = withContext(Dispatchers.IO) {
        mutex.withLock { readCategories() }
    }

    suspend fun add(label: String): List<DrinkCategory> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cleanLabel = label.trim().ifBlank { return@withLock readCategories() }.take(28)
            val current = readCategories()
            if (current.any { it.label.equals(cleanLabel, ignoreCase = true) || it.id == cleanLabel.toCategoryId() }) {
                return@withLock current
            }

            val next = current + DrinkCategory.custom(cleanLabel, nextColor(current.size))
            writeCustomCategories(next.filterNot { it.builtIn })
            next
        }
    }

    suspend fun delete(id: String): List<DrinkCategory> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readCategories()
            val next = current.filterNot { it.id == id && !it.builtIn }
            writeCustomCategories(next.filterNot { it.builtIn })
            next
        }
    }

    private fun readCategories(): List<DrinkCategory> {
        if (!storeFile.exists()) return DrinkCategory.defaults
        val custom = runCatching {
            val array = JSONArray(storeFile.readText())
            List(array.length()) { index ->
                array.optJSONObject(index)?.toCategoryOrNull()
            }.filterNotNull()
        }.getOrDefault(emptyList())

        val defaults = DrinkCategory.defaults
        val customSorted = custom
            .filterNot { customCategory -> defaults.any { it.id == customCategory.id } }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
        return defaults + customSorted
    }

    private fun writeCustomCategories(categories: List<DrinkCategory>) {
        storeDir.mkdirs()
        val temp = File(storeDir, "${storeFile.name}.tmp")
        temp.writeText(JSONArray(categories.map { it.toJson() }).toString(2))
        if (!temp.renameTo(storeFile)) {
            temp.copyTo(storeFile, overwrite = true)
            temp.delete()
        }
    }

    private fun nextColor(index: Int): Int {
        val palette = listOf(
            0xFFE9D7FF.toInt(),
            0xFFFFD7EB.toInt(),
            0xFFDDF4FF.toInt(),
            0xFFFFE2C7.toInt(),
            0xFFD8F5E5.toInt(),
            0xFFFFF0B8.toInt(),
            0xFFE8E1D4.toInt(),
        )
        return palette[index % palette.size]
    }
}

private fun DrinkCategory.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("label", label)
    .put("colorArgb", colorArgb)

private fun JSONObject.toCategoryOrNull(): DrinkCategory? = runCatching {
    val label = optString("label").trim().take(28)
    if (label.isBlank()) return@runCatching null
    DrinkCategory(
        id = optString("id", label.toCategoryId()).ifBlank { label.toCategoryId() },
        label = label,
        colorArgb = optInt("colorArgb", 0xFFD8EFF1.toInt()),
        builtIn = false,
    )
}.getOrNull()
