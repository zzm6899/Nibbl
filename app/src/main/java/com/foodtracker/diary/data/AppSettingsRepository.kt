package com.foodtracker.diary.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AppSettings(
    val weekStartsOnMonday: Boolean = false,
    val ownerId: String = UUID.randomUUID().toString(),
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val username: String = "",
    val shareHost: String = ShareLinkTokenHelper.DEFAULT_SHARE_HOST,
    val hasSeenOnboarding: Boolean = false,
) {
    companion object {
        const val DEFAULT_DISPLAY_NAME = "Me"
    }
}

typealias AppSettingsStore = AppSettingsRepository

class AppSettingsRepository(private val context: Context) {
    private val settingsDir = File(context.filesDir, "settings").apply { mkdirs() }
    private val storeFile = File(settingsDir, "app_settings.json")
    private val mutex = Mutex()

    suspend fun settings(): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock { readSettings() }
    }

    suspend fun save(settings: AppSettings): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normalized = settings.normalized()
            writeSettings(normalized)
            normalized
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(readSettings()).normalized()
            writeSettings(next)
            next
        }
    }

    suspend fun reset(): AppSettings = save(AppSettings())

    private fun readSettings(): AppSettings {
        if (!storeFile.exists()) return AppSettings()
        val raw = runCatching { storeFile.readText() }.getOrDefault("")
        if (raw.isBlank()) return AppSettings()

        return runCatching {
            JSONObject(raw).toAppSettings()
        }.getOrDefault(AppSettings())
            .normalized()
    }

    private fun writeSettings(settings: AppSettings) {
        settingsDir.mkdirs()
        val temp = File(settingsDir, "${storeFile.name}.tmp")
        temp.writeText(settings.toJson().toString(2))
        if (!temp.renameTo(storeFile)) {
            temp.copyTo(storeFile, overwrite = true)
            if (!temp.delete()) temp.deleteOnExit()
        }
    }
}

private fun AppSettings.normalized(): AppSettings =
    copy(
        ownerId = ownerId.trim().ifBlank { UUID.randomUUID().toString() }.take(64),
        displayName = displayName.trim().ifBlank { AppSettings.DEFAULT_DISPLAY_NAME }.take(48),
        username = username.toFriendInviteCode(),
        shareHost = ShareLinkTokenHelper.normalizeShareHost(shareHost)
            .replace("https://api.nibbl.z2hs.au", ShareLinkTokenHelper.DEFAULT_SHARE_HOST)
            .replace("https://sipday.local", ShareLinkTokenHelper.DEFAULT_SHARE_HOST)
            .replace("https://foodtracker.local", ShareLinkTokenHelper.DEFAULT_SHARE_HOST),
    )

private fun AppSettings.toJson(): JSONObject = JSONObject()
    .put("weekStartsOnMonday", weekStartsOnMonday)
    .put("ownerId", ownerId)
    .put("displayName", displayName)
    .put("username", username)
    .put("shareHost", shareHost)
    .put("hasSeenOnboarding", hasSeenOnboarding)

private fun JSONObject.toAppSettings(): AppSettings =
    AppSettings(
        weekStartsOnMonday = optBoolean("weekStartsOnMonday", optString("weekStart").equals("monday", ignoreCase = true)),
        ownerId = optNonBlankString("ownerId")
            ?: optNonBlankString("installId")
            ?: UUID.randomUUID().toString(),
        displayName = optNonBlankString("displayName")
            ?: optNonBlankString("name")
            ?: AppSettings.DEFAULT_DISPLAY_NAME,
        username = optNonBlankString("username")
            ?: optNonBlankString("userTag")
            ?: "",
        shareHost = optNonBlankString("shareHost")
            ?: optNonBlankString("host")
            ?: ShareLinkTokenHelper.DEFAULT_SHARE_HOST,
        hasSeenOnboarding = optBoolean("hasSeenOnboarding", false),
    )

private fun JSONObject.optNonBlankString(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }
