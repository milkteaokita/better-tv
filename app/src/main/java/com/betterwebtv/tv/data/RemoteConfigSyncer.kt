package com.betterwebtv.tv.data

import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.model.ChannelJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

sealed class SyncResult {
    data class Success(val channels: List<Channel>) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

object RemoteConfigSyncer {

    suspend fun fetchSync(urlString: String): SyncResult = withContext(Dispatchers.IO) {
        val trimmed = urlString.trim()
        if (!ChannelJsonCodec.isValidHttpUrl(trimmed)) {
            return@withContext SyncResult.Failure("網址格式不正確（需以 http:// 或 https:// 開頭）")
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL(trimmed)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "BetterWebTV/1.0")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext SyncResult.Failure("伺服器回應錯誤 ($code)")
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }

            val parsed = ChannelJsonCodec.parseChannels(body)
            if (parsed.isEmpty()) {
                return@withContext SyncResult.Failure("遠端內容沒有可用的頻道資料")
            }
            SyncResult.Success(parsed)
        } catch (e: Exception) {
            SyncResult.Failure(e.message ?: "網路連線失敗")
        } finally {
            conn?.disconnect()
        }
    }
}
