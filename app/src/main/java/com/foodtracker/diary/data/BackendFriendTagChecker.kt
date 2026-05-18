package com.foodtracker.diary.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object BackendFriendTagChecker {
    suspend fun isAvailable(shareHost: String, tag: String): Boolean = withContext(Dispatchers.IO) {
        val cleanTag = tag.toFriendInviteCode()
        if (cleanTag.length < 3) return@withContext false

        runCatching {
            val encoded = URLEncoder.encode(cleanTag, Charsets.UTF_8.name())
            val endpoint = "${ShareLinkTokenHelper.normalizeShareHost(shareHost)}/api/nibbl/friends/available?tag=$encoded"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body).optBoolean("available", true)
        }.getOrDefault(true)
    }
}

fun String.toFriendInviteCode(): String =
    trim().filter(Char::isLetterOrDigit).lowercase().take(10)
