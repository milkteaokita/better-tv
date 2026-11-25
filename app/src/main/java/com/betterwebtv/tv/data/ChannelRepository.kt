package com.betterwebtv.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.betterwebtv.tv.model.AppConfig
import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.model.ChannelJsonCodec

class ChannelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getChannels(): List<Channel> {
        val raw = prefs.getString(KEY_CHANNELS_JSON, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ChannelJsonCodec.parseChannels(raw) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                return parsed
            }
        }
        val defaults = defaultChannels()
        saveChannels(defaults)
        return defaults
    }

    @Synchronized
    fun saveChannels(channels: List<Channel>) {
        val sorted = ChannelJsonCodec.sortChannels(channels)
        prefs.edit().putString(KEY_CHANNELS_JSON, ChannelJsonCodec.encodeChannels(sorted)).apply()
    }

    @Synchronized
    fun getRemoteJsonUrl(): String {
        return prefs.getString(KEY_REMOTE_JSON_URL, "").orEmpty().trim()
    }

    @Synchronized
    fun setRemoteJsonUrl(url: String) {
        prefs.edit().putString(KEY_REMOTE_JSON_URL, url.trim()).apply()
    }

    @Synchronized
    fun isAutoSyncOnStart(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    @Synchronized
    fun setAutoSyncOnStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    @Synchronized
    fun getLastChannelId(): String? {
        return prefs.getString(KEY_LAST_CHANNEL_ID, null)
    }

    @Synchronized
    fun setLastChannelId(channelId: String) {
        prefs.edit().putString(KEY_LAST_CHANNEL_ID, channelId).apply()
    }

    @Synchronized
    fun getAppConfig(): AppConfig {
        return AppConfig(
            channels = getChannels(),
            remoteJsonUrl = getRemoteJsonUrl(),
            autoSyncOnStart = isAutoSyncOnStart()
        )
    }

    @Synchronized
    fun saveAppConfig(config: AppConfig) {
        if (config.channels.isNotEmpty()) {
            saveChannels(config.channels)
        }
        setRemoteJsonUrl(config.remoteJsonUrl)
        setAutoSyncOnStart(config.autoSyncOnStart)
    }

    fun getInitialChannel(): Channel {
        val channels = getChannels()
        return ChannelJsonCodec.findById(channels, getLastChannelId()) ?: channels.first()
    }

    fun findByNumber(number: Int): Channel? {
        return ChannelJsonCodec.findByNumber(getChannels(), number)
    }

    fun findById(id: String?): Channel? {
        return ChannelJsonCodec.findById(getChannels(), id)
    }

    fun getNextChannel(currentId: String?): Channel? {
        return ChannelJsonCodec.getNextChannel(getChannels(), currentId)
    }

    fun getPreviousChannel(currentId: String?): Channel? {
        return ChannelJsonCodec.getPreviousChannel(getChannels(), currentId)
    }

    companion object {
        private const val PREFS_NAME = "better_webtv_prefs"
        private const val KEY_CHANNELS_JSON = "channels_json"
        private const val KEY_REMOTE_JSON_URL = "remote_json_url"
        private const val KEY_AUTO_SYNC = "auto_sync_on_start"
        private const val KEY_LAST_CHANNEL_ID = "last_channel_id"

        fun defaultChannels(): List<Channel> = listOf(
            Channel(
                id = "sample_1",
                number = 1,
                name = "範例頻道 1",
                url = "https://example.com/channel/1",
                logoUrl = "",
                badgeColor = "#1D4ED8"
            ),
            Channel(
                id = "sample_2",
                number = 2,
                name = "範例頻道 2",
                url = "https://example.com/channel/2",
                logoUrl = "",
                badgeColor = "#0F766E"
            ),
            Channel(
                id = "sample_3",
                number = 3,
                name = "範例頻道 3",
                url = "https://example.com/channel/3",
                logoUrl = "",
                badgeColor = "#B91C1C"
            )
        )
    }
}
