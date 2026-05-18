package com.foodtracker.diary.data

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.CRC32

data class DayShareToken(
    val date: LocalDate,
    val displayName: String,
    val issuedAtMillis: Long,
)

data class DayShareLink(
    val date: LocalDate,
    val token: String,
    val url: String,
)

object ShareLinkTokenHelper {
    const val DEFAULT_SHARE_HOST = "https://api.nibbl.z2hs.au"

    private const val TOKEN_VERSION = 1
    private val TOKEN_FLAG = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun createDayShareLink(
        date: LocalDate,
        settings: AppSettings,
        issuedAtMillis: Long = System.currentTimeMillis(),
    ): DayShareLink {
        val token = createDayToken(date, settings.displayName, issuedAtMillis)
        return DayShareLink(
            date = date,
            token = token,
            url = createDayUrl(date, settings.shareHost, token),
        )
    }

    fun createDayUrl(
        date: LocalDate,
        shareHost: String,
        displayName: String,
        issuedAtMillis: Long = System.currentTimeMillis(),
    ): String = createDayUrl(date, shareHost, createDayToken(date, displayName, issuedAtMillis))

    fun createDayToken(
        date: LocalDate,
        displayName: String,
        issuedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val payload = JSONObject()
            .put("version", TOKEN_VERSION)
            .put("date", date.toString())
            .put("displayName", displayName.trim().ifBlank { AppSettings.DEFAULT_DISPLAY_NAME }.take(48))
            .put("issuedAtMillis", issuedAtMillis)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val encodedPayload = Base64.encodeToString(payload, TOKEN_FLAG)
        return "$encodedPayload.${checksum(payload)}"
    }

    fun parseDayUrl(url: String): DayShareToken? =
        tokenFromDayUrl(url)?.let(::parseDayToken)

    fun tokenFromDayUrl(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        return uri.rawQuery
            ?.split("&")
            ?.mapNotNull { parameter ->
                val separator = parameter.indexOf("=")
                if (separator < 0) return@mapNotNull null
                val key = parameter.substring(0, separator).urlDecode()
                val value = parameter.substring(separator + 1).urlDecode()
                if (key == "token") value else null
            }
            ?.firstOrNull { it.isNotBlank() }
    }

    fun parseDayToken(token: String): DayShareToken? {
        val parts = token.trim().split(".")
        if (parts.size != 2 || parts.any { it.isBlank() }) return null

        val payload = runCatching { Base64.decode(parts[0], TOKEN_FLAG) }.getOrNull() ?: return null
        if (checksum(payload) != parts[1]) return null

        return runCatching {
            val json = JSONObject(String(payload, StandardCharsets.UTF_8))
            if (json.optInt("version") != TOKEN_VERSION) return null
            DayShareToken(
                date = LocalDate.parse(json.getString("date")),
                displayName = json.optString("displayName", AppSettings.DEFAULT_DISPLAY_NAME).trim()
                    .ifBlank { AppSettings.DEFAULT_DISPLAY_NAME },
                issuedAtMillis = json.optLong("issuedAtMillis"),
            )
        }.getOrNull()
    }

    fun normalizeShareHost(shareHost: String): String {
        val trimmed = shareHost.trim().trimEnd('/')
        if (trimmed.isBlank()) return DEFAULT_SHARE_HOST

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return DEFAULT_SHARE_HOST
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return DEFAULT_SHARE_HOST

        val normalizedPath = uri.path.orEmpty().trimEnd('/')
        return URI(uri.scheme.lowercase(), uri.userInfo, uri.host.lowercase(), uri.port, normalizedPath, null, null)
            .toString()
            .trimEnd('/')
            .ifBlank { DEFAULT_SHARE_HOST }
    }

    private fun createDayUrl(date: LocalDate, shareHost: String, token: String): String {
        val base = normalizeShareHost(shareHost)
        return "$base/day/$date?token=${token.urlEncode()}"
    }

    private fun checksum(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toString(36)
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}
