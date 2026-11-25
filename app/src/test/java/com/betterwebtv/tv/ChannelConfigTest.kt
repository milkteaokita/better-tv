package com.betterwebtv.tv

import com.betterwebtv.tv.model.AppConfig
import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.model.ChannelJsonCodec
import com.betterwebtv.tv.player.ReloadThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigTest {

    @Test
    fun parseChannels_supportsWrapperObjectAndSortsAscending() {
        val raw = """
            {
              "channels": [
                {
                  "id": "sample_3",
                  "number": 3,
                  "name": "範例頻道 3",
                  "url": "https://example.com/channel/3"
                },
                {
                  "id": "sample_1",
                  "number": 1,
                  "name": "範例頻道 1",
                  "url": "https://example.com/channel/1",
                  "logoUrl": "https://example.com/logo1.png",
                  "badgeColor": "#15803d"
                }
              ]
            }
        """.trimIndent()

        val channels = ChannelJsonCodec.parseChannels(raw)
        assertEquals(2, channels.size)
        assertEquals(1, channels[0].number)
        assertEquals("範例頻道 1", channels[0].name)
        assertEquals("#15803D", channels[0].badgeColor)
        assertEquals(3, channels[1].number)
        assertEquals("範例頻道 3", channels[1].name)
    }

    @Test
    fun parseChannels_supportsBareArrayAndFiltersInvalidEntries() {
        val raw = """
            [
              {"number": 2, "name": "範例頻道 2", "url": "https://example.com/channel/2"},
              {"number": 0, "name": "無效台號", "url": "https://example.com/channel/2"},
              {"number": 15, "name": "無效網址", "url": "ftp://invalid.local/stream"},
              {"number": 20, "name": "", "url": "https://example.com/channel/2"}
            ]
        """.trimIndent()

        val channels = ChannelJsonCodec.parseChannels(raw)
        assertEquals(1, channels.size)
        assertEquals(2, channels[0].number)
        assertEquals("範例頻道 2", channels[0].name)
        assertTrue(channels[0].id.isNotBlank())
    }

    @Test
    fun roundTripAppConfig_preservesAllFields() {
        val config = AppConfig(
            channels = listOf(
                Channel("sample_1", 1, "範例頻道 1", "https://example.com/channel/1", "https://example.com/logo1.svg", "#15803D"),
                Channel("sample_2", 2, "範例頻道 2", "https://example.com/channel/2", "", "#1D4ED8")
            ),
            remoteJsonUrl = "https://gist.githubusercontent.com/demo/raw/channels.json",
            autoSyncOnStart = false
        )

        val encoded = ChannelJsonCodec.encodeConfig(config)
        val decoded = ChannelJsonCodec.parseConfig(encoded)

        assertEquals(config.remoteJsonUrl, decoded.remoteJsonUrl)
        assertEquals(config.autoSyncOnStart, decoded.autoSyncOnStart)
        assertEquals(2, decoded.channels.size)
        assertEquals(config.channels[0], decoded.channels[0])
        assertEquals(config.channels[1], decoded.channels[1])
    }

    @Test
    fun navigationHelpers_wrapAroundAndLookupByNumber() {
        val list = listOf(
            Channel("sample_3", 3, "範例頻道 3", "https://example.com/channel/3"),
            Channel("sample_1", 1, "範例頻道 1", "https://example.com/channel/1"),
            Channel("sample_2", 2, "範例頻道 2", "https://example.com/channel/2")
        )

        assertEquals("範例頻道 2", ChannelJsonCodec.findByNumber(list, 2)?.name)
        assertNull(ChannelJsonCodec.findByNumber(list, 99))

        assertEquals("sample_2", ChannelJsonCodec.getNextChannel(list, "sample_1")?.id)
        assertEquals("sample_1", ChannelJsonCodec.getNextChannel(list, "sample_3")?.id)

        assertEquals("sample_3", ChannelJsonCodec.getPreviousChannel(list, "sample_1")?.id)
        assertEquals("sample_2", ChannelJsonCodec.getPreviousChannel(list, "sample_3")?.id)
    }

    @Test
    fun reloadThrottle_blocksRapidPressesAndAllowsAfterCooldownOrReset() {
        val throttle = ReloadThrottle(cooldownMs = 3_000L)

        assertEquals(0L, throttle.remainingCooldownMs(1_000L))
        assertTrue(throttle.tryAcquire(1_000L))

        assertFalse(throttle.tryAcquire(1_500L))
        assertEquals(2_500L, throttle.remainingCooldownMs(1_500L))
        assertFalse(throttle.tryAcquire(3_999L))
        assertEquals(1L, throttle.remainingCooldownMs(3_999L))

        // Allowed once 3000ms has elapsed, and starts a new cooldown window
        assertTrue(throttle.tryAcquire(4_000L))
        assertFalse(throttle.tryAcquire(5_000L))

        // Reset clears cooldown immediately (e.g. when switching channels)
        throttle.reset()
        assertEquals(0L, throttle.remainingCooldownMs(5_000L))
        assertTrue(throttle.tryAcquire(5_000L))
    }
}
