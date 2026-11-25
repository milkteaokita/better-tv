package com.betterwebtv.tv.server

import android.content.Context
import com.betterwebtv.tv.data.ChannelRepository
import com.betterwebtv.tv.data.RemoteConfigSyncer
import com.betterwebtv.tv.data.SyncResult
import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.model.ChannelJsonCodec
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketException

class AdminHttpServer(
    private val context: Context,
    private val repository: ChannelRepository,
    private val callbacks: Callbacks,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    interface Callbacks {
        fun getCurrentChannelId(): String?
        fun getCurrentWebUrl(): String
        fun onConfigUpdated(channels: List<Channel>)
        fun onPlayChannelRequested(channel: Channel)
    }

    private val adminHtml: String by lazy {
        context.assets.open("admin.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        val method = session.method

        if (method == Method.OPTIONS) {
            return addCorsHeaders(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        return try {
            val response = when {
                method == Method.GET && (path == "/" || path == "/index.html") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", adminHtml)
                }
                method == Method.GET && path == "/api/config" -> handleGetConfig()
                method == Method.POST && path == "/api/config" -> handlePostConfig(session)
                method == Method.POST && path == "/api/sync" -> handlePostSync(session)
                method == Method.POST && path == "/api/play" -> handlePostPlay(session)
                else -> jsonError(Response.Status.NOT_FOUND, "找不到路徑：$path")
            }
            addCorsHeaders(response)
        } catch (e: Exception) {
            addCorsHeaders(jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "伺服器內部錯誤"))
        }
    }

    private fun handleGetConfig(): Response {
        val config = repository.getAppConfig()
        val payload = JSONObject().apply {
            put("channels", JSONArray(ChannelJsonCodec.encodeChannels(config.channels)))
            put("remoteJsonUrl", config.remoteJsonUrl)
            put("autoSyncOnStart", config.autoSyncOnStart)
            put("currentChannelId", callbacks.getCurrentChannelId() ?: "")
            put("currentWebUrl", callbacks.getCurrentWebUrl())
            put("port", listeningPort)
        }
        return jsonOk(payload)
    }

    private fun handlePostConfig(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        if (body.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "請求內容為空")
        }

        val parsedConfig = runCatching { ChannelJsonCodec.parseConfig(body) }.getOrElse {
            return jsonError(Response.Status.BAD_REQUEST, "JSON 格式錯誤：${it.message}")
        }

        if (parsedConfig.channels.isEmpty()) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "至少需要一個有效頻道（台號 1~9999、名稱與 http/https 網址不可空白）"
            )
        }

        repository.saveAppConfig(parsedConfig)
        val savedChannels = repository.getChannels()
        callbacks.onConfigUpdated(savedChannels)

        val result = JSONObject().apply {
            put("ok", true)
            put("count", savedChannels.size)
            put("channels", JSONArray(ChannelJsonCodec.encodeChannels(savedChannels)))
            put("remoteJsonUrl", repository.getRemoteJsonUrl())
            put("autoSyncOnStart", repository.isAutoSyncOnStart())
        }
        return jsonOk(result)
    }

    private fun handlePostSync(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val reqObj = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() else null
        val requestedUrl = reqObj?.optString("url", "")?.trim().orEmpty()
        val targetUrl = requestedUrl.ifBlank { repository.getRemoteJsonUrl() }

        if (targetUrl.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "請先填寫遠端 JSON 網址")
        }

        val syncResult = runBlocking { RemoteConfigSyncer.fetchSync(targetUrl) }
        return when (syncResult) {
            is SyncResult.Success -> {
                repository.setRemoteJsonUrl(targetUrl)
                repository.saveChannels(syncResult.channels)
                val updated = repository.getChannels()
                callbacks.onConfigUpdated(updated)
                jsonOk(
                    JSONObject().apply {
                        put("ok", true)
                        put("count", updated.size)
                        put("message", "同步成功，共 ${updated.size} 個頻道")
                        put("channels", JSONArray(ChannelJsonCodec.encodeChannels(updated)))
                        put("remoteJsonUrl", targetUrl)
                    }
                )
            }
            is SyncResult.Failure -> {
                jsonError(Response.Status.BAD_REQUEST, "同步失敗：${syncResult.message}")
            }
        }
    }

    private fun handlePostPlay(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val reqObj = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "JSON 格式錯誤")

        val channelId = reqObj.optString("channelId", "").trim()
        val number = reqObj.optInt("number", -1)
        val channel = repository.findById(channelId) ?: repository.findByNumber(number)
            ?: return jsonError(Response.Status.NOT_FOUND, "找不到指定頻道")

        callbacks.onPlayChannelRequested(channel)
        return jsonOk(
            JSONObject().apply {
                put("ok", true)
                put("channelId", channel.id)
                put("number", channel.number)
                put("name", channel.name)
            }
        )
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ""
        val buffer = ByteArray(contentLength)
        var totalRead = 0
        val stream = session.inputStream
        while (totalRead < contentLength) {
            val read = stream.read(buffer, totalRead, contentLength - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return String(buffer, 0, totalRead, Charsets.UTF_8)
    }

    private fun jsonOk(obj: JSONObject): Response {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val obj = JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        return response
    }

    companion object {
        const val DEFAULT_PORT = 8088

        fun startWithFallback(
            context: Context,
            repository: ChannelRepository,
            callbacks: Callbacks
        ): AdminHttpServer? {
            for (port in DEFAULT_PORT..(DEFAULT_PORT + 7)) {
                val server = AdminHttpServer(context, repository, callbacks, port)
                try {
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: IOException) {
                    runCatching { server.stop() }
                } catch (_: SocketException) {
                    runCatching { server.stop() }
                }
            }
            return null
        }
    }
}
