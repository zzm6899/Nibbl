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
    val profileImagePath: String? = null,
    val apiToken: String = "",
    val shareHost: String = ShareLinkTokenHelper.DEFAULT_SHARE_HOST,
    val hasSeenOnboarding: Boolean = false,
    val plusUnlocked: Boolean = false,
    val proActive: Boolean = false,
    val lastPurchaseSyncMillis: Long = 0L,
    val backgroundRemovalMonth: String = "",
    val backgroundRemovalsThisMonth: Int = 0,
    val themeId: String = "pastel",
    val stickerPack: String = "sweet",
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

    suspend fun recordBackgroundRemoval(monthKey: String): AppSettings = update {
        val currentCount = if (it.backgroundRemovalMonth == monthKey) it.backgroundRemovalsThisMonth else 0
        it.copy(backgroundRemovalMonth = monthKey, backgroundRemovalsThisMonth = currentCount + 1)
    }

    suspend fun saveProfileImage(bytes: ByteArray, suffix: String = ".jpg"): AppSettings = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Profile image cannot be empty" }
        mutex.withLock {
            val current = readSettings()
            val safeSuffix = suffix.trim().lowercase().let { if (it.startsWith(".")) it else ".$it" }
                .filter { it.isLetterOrDigit() || it == '.' }
                .takeIf { it in setOf(".jpg", ".jpeg", ".png", ".webp") }
                ?: ".jpg"
            val avatarDir = File(settingsDir, "profile").apply { mkdirs() }
            val file = File(avatarDir, "profile$safeSuffix")
            val temp = File(avatarDir, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) temp.deleteOnExit()
            }
            val next = current.copy(profileImagePath = file.absolutePath).normalized()
            writeSettings(next)
            next
        }
    }

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
        profileImagePath = profileImagePath?.trim()?.takeIf { it.isNotBlank() },
        themeId = themeId.trim().lowercase().takeIf { it in setOf("pastel", "berry", "mint", "sunny") } ?: "pastel",
        stickerPack = stickerPack.trim().lowercase().takeIf { it in setOf("sweet", "cafe", "sparkle", "fresh") } ?: "sweet",
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
    .put("profileImagePath", profileImagePath ?: JSONObject.NULL)
    .put("apiToken", apiToken)
    .put("shareHost", shareHost)
    .put("hasSeenOnboarding", hasSeenOnboarding)
    .put("plusUnlocked", plusUnlocked)
    .put("proActive", proActive)
    .put("lastPurchaseSyncMillis", lastPurchaseSyncMillis)
    .put("backgroundRemovalMonth", backgroundRemovalMonth)
    .put("backgroundRemovalsThisMonth", backgroundRemovalsThisMonth)
    .put("themeId", themeId)
    .put("stickerPack", stickerPack)

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
        profileImagePath = optNonBlankString("profileImagePath")
            ?: optNonBlankString("avatarPath")
            ?: optNonBlankString("photoPath"),
        apiToken = optNonBlankString("apiToken") ?: "",
        shareHost = optNonBlankString("shareHost")
            ?: optNonBlankString("host")
            ?: ShareLinkTokenHelper.DEFAULT_SHARE_HOST,
        hasSeenOnboarding = optBoolean("hasSeenOnboarding", false),
        plusUnlocked = optBoolean("plusUnlocked", false),
        proActive = optBoolean("proActive", false),
        lastPurchaseSyncMillis = optLong("lastPurchaseSyncMillis", 0L),
        backgroundRemovalMonth = optNonBlankString("backgroundRemovalMonth") ?: "",
        backgroundRemovalsThisMonth = optInt("backgroundRemovalsThisMonth", 0),
        themeId = optNonBlankString("themeId") ?: "pastel",
        stickerPack = optNonBlankString("stickerPack") ?: "sweet",
    )

private fun JSONObject.optNonBlankString(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }
