package com.foodtracker.diary.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CafeCrewPerson(
    val displayName: String,
    val id: String = UUID.randomUUID().toString(),
    val inviteCode: String = id.toFriendInviteCode().ifBlank { "friend" },
    val avatarPath: String? = null,
    val remoteAvatarUrl: String? = null,
    val colorHex: String? = null,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    val name: String
        get() = displayName
}

class CafeCrewStore(private val context: Context) {
    private val peopleDir = File(context.filesDir, "people").apply { mkdirs() }
    private val storeFile = File(peopleDir, "cafe_crew.json")
    private val mutex = Mutex()

    suspend fun people(): List<CafeCrewPerson> = withContext(Dispatchers.IO) {
        mutex.withLock { readPeople() }
    }

    suspend fun displayNames(): List<String> = people().map { it.displayName }

    suspend fun save(person: CafeCrewPerson): CafeCrewPerson = withContext(Dispatchers.IO) {
        mutex.withLock { upsertInternal(person) }
    }

    suspend fun update(person: CafeCrewPerson): CafeCrewPerson = save(person)

    suspend fun add(displayName: String): CafeCrewPerson =
        save(CafeCrewPerson(displayName = displayName))

    suspend fun upsertName(displayName: String): CafeCrewPerson = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cleanName = displayName.cleanPersonName()
            require(cleanName.isNotBlank()) { "Friend needs a name" }

            val current = readPeople()
            val existing = current.firstOrNull { it.displayName.samePersonName(cleanName) }
            if (existing != null) return@withLock existing

            upsertInternal(
                CafeCrewPerson(
                    displayName = cleanName,
                    sortOrder = current.nextSortOrder(),
                )
            )
        }
    }

    suspend fun upsertInvite(displayName: String, inviteCode: String, remoteAvatarUrl: String? = null): CafeCrewPerson = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cleanName = displayName.cleanPersonName()
            val cleanCode = inviteCode.toFriendInviteCode()
            require(cleanName.isNotBlank()) { "Friend needs a name" }
            require(cleanCode.isNotBlank()) { "Friend invite needs a code" }

            val current = readPeople()
            val existing = current.firstOrNull { it.inviteCode == cleanCode }
                ?: current.firstOrNull { it.displayName.samePersonName(cleanName) }

            if (existing != null) {
                upsertInternal(existing.copy(displayName = cleanName, inviteCode = cleanCode, remoteAvatarUrl = remoteAvatarUrl ?: existing.remoteAvatarUrl))
            } else {
                upsertInternal(
                    CafeCrewPerson(
                        displayName = cleanName,
                        inviteCode = cleanCode,
                        remoteAvatarUrl = remoteAvatarUrl,
                        sortOrder = current.nextSortOrder(),
                    )
                )
            }
        }
    }

    suspend fun rename(id: String, displayName: String): CafeCrewPerson? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPeople()
            val existing = current.firstOrNull { it.id == id } ?: return@withLock null
            val renamed = existing.copy(displayName = displayName)
            upsertInternal(renamed)
        }
    }

    suspend fun saveAvatarImage(id: String, bytes: ByteArray, suffix: String = ".jpg"): CafeCrewPerson? = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Avatar image cannot be empty" }
        mutex.withLock {
            val current = readPeople()
            val existing = current.firstOrNull { it.id == id } ?: return@withLock null
            val safeSuffix = suffix.trim().lowercase().let { if (it.startsWith(".")) it else ".$it" }
                .filter { it.isLetterOrDigit() || it == '.' }
                .takeIf { it in setOf(".jpg", ".jpeg", ".png", ".webp") }
                ?: ".jpg"
            val avatarDir = File(peopleDir, "avatars").apply { mkdirs() }
            val file = File(avatarDir, "${id.toFriendInviteCode()}$safeSuffix")
            val temp = File(avatarDir, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) temp.deleteOnExit()
            }
            upsertInternal(existing.copy(avatarPath = file.absolutePath))
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPeople()
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return@withLock false

            writePeople(next)
            true
        }
    }

    suspend fun replaceAll(people: List<CafeCrewPerson>): List<CafeCrewPerson> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val normalized = people
                .mapIndexedNotNull { index, person -> person.normalized(now, index).takeIf { it.displayName.isNotBlank() } }
                .deduped()
            writePeople(normalized)
            normalized
        }
    }

    suspend fun ensurePeopleForNames(names: Iterable<String>): List<CafeCrewPerson> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readPeople()
            val existingNames = current.map { it.displayName.normalizedPersonKey() }.toMutableSet()
            var nextSortOrder = current.nextSortOrder()
            val additions = names
                .map { it.cleanPersonName() }
                .filter { it.isNotBlank() }
                .distinctBy { it.normalizedPersonKey() }
                .filter { existingNames.add(it.normalizedPersonKey()) }
                .map { name ->
                    CafeCrewPerson(displayName = name, sortOrder = nextSortOrder++)
                }

            if (additions.isEmpty()) {
                current
            } else {
                (current + additions).also(::writePeople)
            }
        }
    }

    private fun readPeople(): List<CafeCrewPerson> {
        if (!storeFile.exists()) return emptyList()
        val raw = runCatching { storeFile.readText() }.getOrDefault("")
        if (raw.isBlank()) return emptyList()

        val array = runCatching { raw.toPeopleArray() }.getOrDefault(JSONArray())
        return List(array.length()) { index -> array.opt(index).toCafeCrewPersonOrNull(index) }
            .filterNotNull()
            .deduped()
    }

    private fun upsertInternal(person: CafeCrewPerson): CafeCrewPerson {
        val now = System.currentTimeMillis()
        val current = readPeople()
        val existing = current.firstOrNull { it.id == person.id }
        val normalized = person.normalized(now, current.nextSortOrder())
            .copy(createdAtMillis = existing?.createdAtMillis ?: person.createdAtMillis.coerceAtLeast(1L))
        require(normalized.displayName.isNotBlank()) { "Friend needs a name" }

        val next = (current.filterNot { it.id == normalized.id } + normalized)
            .deduped()
        writePeople(next)
        return next.first { it.id == normalized.id }
    }

    private fun writePeople(people: List<CafeCrewPerson>) {
        peopleDir.mkdirs()
        val temp = File(peopleDir, "${storeFile.name}.tmp")
        temp.writeText(JSONArray(people.map { it.toJson() }).toString(2))
        if (!temp.renameTo(storeFile)) {
            temp.copyTo(storeFile, overwrite = true)
            if (!temp.delete()) temp.deleteOnExit()
        }
    }
}

private fun CafeCrewPerson.normalized(now: Long, fallbackSortOrder: Int): CafeCrewPerson =
    copy(
        displayName = displayName.cleanPersonName(),
        inviteCode = inviteCode.toFriendInviteCode().ifBlank { id.toFriendInviteCode().ifBlank { "friend" } },
        avatarPath = avatarPath?.trim()?.takeIf { it.isNotBlank() },
        remoteAvatarUrl = remoteAvatarUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") },
        colorHex = colorHex?.trim()?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")) },
        sortOrder = sortOrder.takeIf { it >= 0 } ?: fallbackSortOrder,
        createdAtMillis = createdAtMillis.coerceAtLeast(1L),
        updatedAtMillis = now,
    )

private fun List<CafeCrewPerson>.deduped(): List<CafeCrewPerson> =
    sortedWith(compareBy<CafeCrewPerson> { it.sortOrder }.thenBy { it.displayName.lowercase() })
        .distinctBy { it.id }
        .distinctBy { it.displayName.normalizedPersonKey() }

private fun List<CafeCrewPerson>.nextSortOrder(): Int =
    (maxOfOrNull { it.sortOrder } ?: -1) + 1

private fun CafeCrewPerson.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("displayName", displayName)
    .put("inviteCode", inviteCode)
    .put("avatarPath", avatarPath ?: JSONObject.NULL)
    .put("remoteAvatarUrl", remoteAvatarUrl ?: JSONObject.NULL)
    .put("colorHex", colorHex ?: JSONObject.NULL)
    .put("isFavorite", isFavorite)
    .put("sortOrder", sortOrder)
    .put("createdAtMillis", createdAtMillis)
    .put("updatedAtMillis", updatedAtMillis)

private fun String.toPeopleArray(): JSONArray {
    val trimmed = trim()
    if (trimmed.startsWith("[")) return JSONArray(trimmed)
    if (trimmed.startsWith("{")) {
        val objectValue = JSONObject(trimmed)
        return objectValue.optJSONArray("people")
            ?: objectValue.optJSONArray("crew")
            ?: objectValue.optJSONArray("friends")
            ?: JSONArray()
    }
    return JSONArray()
}

private fun Any?.toCafeCrewPersonOrNull(index: Int): CafeCrewPerson? = runCatching {
    when (this) {
        is JSONObject -> {
            val parsedId = optNonBlankString("id") ?: UUID.nameUUIDFromBytes(toString().toByteArray()).toString()
            CafeCrewPerson(
                id = parsedId,
                inviteCode = optNonBlankString("inviteCode")
                    ?: optNonBlankString("code")
                    ?: parsedId.toFriendInviteCode(),
                avatarPath = optNonBlankString("avatarPath")
                    ?: optNonBlankString("photoPath")
                    ?: optNonBlankString("profileImagePath"),
                remoteAvatarUrl = optNonBlankString("remoteAvatarUrl")
                    ?: optNonBlankString("avatarUrl")
                    ?: optNonBlankString("profileImageUrl"),
                displayName = optNonBlankString("displayName")
                    ?: optNonBlankString("name")
                    ?: optNonBlankString("friendName")
                    ?: "",
                colorHex = optNonBlankString("colorHex") ?: optNonBlankString("color"),
                isFavorite = optBoolean("isFavorite", optBoolean("favorite", false)),
                sortOrder = optInt("sortOrder", index),
                createdAtMillis = optLong("createdAtMillis", optLong("createdAt", System.currentTimeMillis())),
                updatedAtMillis = optLong("updatedAtMillis", optLong("updatedAt", System.currentTimeMillis())),
            )
        }
        is String -> CafeCrewPerson(displayName = this, sortOrder = index)
        else -> null
    }
}.getOrNull()

private fun JSONObject.optNonBlankString(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }

private fun String.cleanPersonName(): String =
    trim().replace(Regex("\\s+"), " ").take(48)

private fun String.normalizedPersonKey(): String =
    cleanPersonName().lowercase()

private fun String.samePersonName(other: String): Boolean =
    normalizedPersonKey() == other.normalizedPersonKey()
