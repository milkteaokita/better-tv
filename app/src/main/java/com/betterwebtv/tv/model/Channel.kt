package com.betterwebtv.tv.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val url: String,
    val logoUrl: String = "",
    val badgeColor: String = ""
)

data class AppConfig(
    val channels: List<Channel>,
    val remoteJsonUrl: String = "",
    val autoSyncOnStart: Boolean = true
)

object ChannelJsonCodec {

    private val defaultPalette = listOf(
        "#1D4ED8", // Royal Blue
        "#0F766E", // Deep Teal
        "#B91C1C", // Broadcast Red
        "#7C3AED", // Violet
        "#C2410C", // Warm Orange
        "#0369A1", // Sky Blue
        "#4338CA", // Indigo
        "#15803D"  // Emerald
    )

    fun sortChannels(channels: List<Channel>): List<Channel> {
        return channels.sortedWith(compareBy<Channel> { it.number }.thenBy { it.name })
    }

    fun findByNumber(channels: List<Channel>, number: Int): Channel? {
        return channels.firstOrNull { it.number == number }
    }

    fun findById(channels: List<Channel>, id: String?): Channel? {
        if (id.isNullOrBlank()) return null
        return channels.firstOrNull { it.id == id }
    }

    fun getNextChannel(channels: List<Channel>, currentId: String?): Channel? {
        val sorted = sortChannels(channels)
        if (sorted.isEmpty()) return null
        val index = sorted.indexOfFirst { it.id == currentId }
        return if (index < 0) sorted.first() else sorted[(index + 1) % sorted.size]
    }

    fun getPreviousChannel(channels: List<Channel>, currentId: String?): Channel? {
        val sorted = sortChannels(channels)
        if (sorted.isEmpty()) return null
        val index = sorted.indexOfFirst { it.id == currentId }
        return if (index < 0) sorted.first() else sorted[(index - 1 + sorted.size) % sorted.size]
    }

    fun encodeChannels(channels: List<Channel>): String {
        val array = JSONArray()
        for (channel in sortChannels(channels)) {
            array.put(channelToJson(channel))
        }
        return array.toString()
    }

    fun encodeConfig(config: AppConfig): String {
        val root = JSONObject()
        val array = JSONArray()
        for (channel in sortChannels(config.channels)) {
            array.put(channelToJson(channel))
        }
        root.put("channels", array)
        root.put("remoteJsonUrl", config.remoteJsonUrl.trim())
        root.put("autoSyncOnStart", config.autoSyncOnStart)
        return root.toString(2)
    }

    fun parseChannels(rawJson: String): List<Channel> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("channels") ?: return emptyList()
            }
            else -> return emptyList()
        }

        val parsed = mutableListOf<Channel>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val number = item.optInt("number", -1)
            val name = item.optString("name", "").trim()
            val url = item.optString("url", "").trim()
            if (number !in 1..9999 || name.isEmpty() || !isValidHttpUrl(url)) {
                continue
            }
            val rawId = item.optString("id", "").trim()
            var id = rawId.ifEmpty { "ch_${number}_${UUID.randomUUID().toString().take(6)}" }
            while (!seenIds.add(id)) {
                id = "${id}_${i}"
            }
            val logoUrl = item.optString("logoUrl", "").trim()
                .let { if (it.isEmpty() || isValidHttpUrl(it)) it else "" }
            val badgeColor = normalizeHexColor(item.optString("badgeColor", "").trim())
                ?: pickBadgeColor(number, name)
            parsed.add(
                Channel(
                    id = id,
                    number = number,
                    name = name,
                    url = url,
                    logoUrl = logoUrl,
                    badgeColor = badgeColor
                )
            )
        }
        return sortChannels(parsed)
    }

    fun parseConfig(rawJson: String): AppConfig {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("[")) {
            return AppConfig(channels = parseChannels(trimmed))
        }
        val obj = JSONObject(trimmed)
        val channels = parseChannels(trimmed)
        val remoteUrl = obj.optString("remoteJsonUrl", "").trim()
        val validRemoteUrl = if (remoteUrl.isEmpty() || isValidHttpUrl(remoteUrl)) remoteUrl else ""
        val autoSync = if (obj.has("autoSyncOnStart")) obj.optBoolean("autoSyncOnStart", true) else true
        return AppConfig(
            channels = channels,
            remoteJsonUrl = validRemoteUrl,
            autoSyncOnStart = autoSync
        )
    }

    fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun pickBadgeColor(number: Int, name: String): String {
        val hash = ((number * 31) + name.hashCode()) and 0x7FFFFFFF
        return defaultPalette[hash % defaultPalette.size]
    }

    private fun normalizeHexColor(color: String): String? {
        if (color.isEmpty()) return null
        val normalized = if (color.startsWith("#")) color else "#$color"
        return if (Regex("^#[0-9A-Fa-f]{6}$").matches(normalized)) normalized.uppercase() else null
    }

    private fun channelToJson(channel: Channel): JSONObject {
        return JSONObject().apply {
            put("id", channel.id)
            put("number", channel.number)
            put("name", channel.name)
            put("url", channel.url)
            put("logoUrl", channel.logoUrl)
            put(
                "badgeColor",
                normalizeHexColor(channel.badgeColor) ?: pickBadgeColor(channel.number, channel.name)
            )
        }
    }
}
