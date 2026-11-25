package com.betterwebtv.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.betterwebtv.tv.data.ChannelRepository
import com.betterwebtv.tv.data.RemoteConfigSyncer
import com.betterwebtv.tv.data.SyncResult
import com.betterwebtv.tv.databinding.ActivityMainBinding
import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.player.ReloadThrottle
import com.betterwebtv.tv.player.WebTvViewHelper
import com.betterwebtv.tv.server.AdminHttpServer
import com.betterwebtv.tv.ui.ChannelDrawerAdapter
import com.betterwebtv.tv.ui.QrCodeGenerator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class UiMode {
        WATCHING,
        DRAWER_OPEN,
        ADMIN_MODAL,
        LOGIN_CURSOR
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ChannelRepository
    private lateinit var webViewHelper: WebTvViewHelper
    private lateinit var drawerAdapter: ChannelDrawerAdapter
    private var adminServer: AdminHttpServer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var uiMode: UiMode = UiMode.WATCHING
    private var currentChannel: Channel? = null
    private var serverUrl: String = ""

    private val digitBuffer = StringBuilder()
    private var lastBackPressMs: Long = 0L
    private var centerDownHandledLongPress = false
    private var needsRefreshOnResume = false
    private var autoRetryChannelId: String? = null
    private var autoRetryCount: Int = 0
    private var lastAutoRetryMs: Long = 0L
    private val okReloadThrottle = ReloadThrottle(ReloadThrottle.DEFAULT_COOLDOWN_MS)

    private val hideOsdRunnable = Runnable {
        binding.channelOsd.animate().alpha(0f).setDuration(250L).withEndAction {
            binding.channelOsd.visibility = View.GONE
            binding.channelOsd.alpha = 1f
        }.start()
    }

    private val hideToastRunnable = Runnable {
        binding.toastBanner.visibility = View.GONE
    }

    private val hideLoadingTimeoutRunnable = Runnable {
        binding.loadingOverlay.visibility = View.GONE
    }

    private val autoCloseDrawerRunnable = Runnable {
        if (uiMode == UiMode.DRAWER_OPEN) {
            closeDrawer()
        }
    }

    private val commitDigitsRunnable = Runnable {
        commitPendingDigits()
    }

    private val longPressAdminRunnable = Runnable {
        if (uiMode == UiMode.WATCHING || uiMode == UiMode.DRAWER_OPEN) {
            centerDownHandledLongPress = true
            openAdminModal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveFullscreen()

        repository = ChannelRepository(this)
        setupWebView()
        setupDrawer()
        setupAdminModal()
        startAdminServer()

        val initialChannel = repository.getInitialChannel()
        playChannel(initialChannel, showOsd = true)
        triggerBackgroundRemoteSync()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        needsRefreshOnResume = true
    }

    override fun onResume() {
        super.onResume()
        webViewHelper.onResume()
        if (needsRefreshOnResume) {
            needsRefreshOnResume = false
            if (uiMode != UiMode.LOGIN_CURSOR && uiMode != UiMode.ADMIN_MODAL) {
                currentChannel?.let { playChannel(it, showOsd = true) }
                triggerBackgroundRemoteSync()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webViewHelper.onPause()
    }

    override fun onStop() {
        super.onStop()
        needsRefreshOnResume = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { adminServer?.stop() }
        webViewHelper.destroy()
        super.onDestroy()
    }

    private fun setupWebView() {
        webViewHelper = WebTvViewHelper(
            context = this,
            webView = binding.webView,
            fullscreenContainer = binding.fullscreenContainer,
            listener = object : WebTvViewHelper.Listener {
                override fun onPageStarted(url: String) {
                    if (uiMode != UiMode.LOGIN_CURSOR) {
                        scheduleLoadingTimeout()
                    }
                }

                override fun onVideoPlaying() {
                    handler.removeCallbacks(hideLoadingTimeoutRunnable)
                    binding.loadingOverlay.visibility = View.GONE
                }

                override fun onPageFinished(url: String) {
                    if (uiMode == UiMode.LOGIN_CURSOR) {
                        binding.loadingOverlay.visibility = View.GONE
                    }
                }

                override fun onNetworkError(description: String) {
                    handler.removeCallbacks(hideLoadingTimeoutRunnable)
                    binding.loadingOverlay.visibility = View.GONE
                    showToast(getString(R.string.network_error_hint), 4500L)
                }

                override fun onPlaybackStalled(reason: String) {
                    if (uiMode != UiMode.WATCHING) return
                    val ch = currentChannel ?: return
                    val now = System.currentTimeMillis()
                    if (autoRetryChannelId != ch.id || now - lastAutoRetryMs > 45_000L) {
                        autoRetryChannelId = ch.id
                        autoRetryCount = 0
                    }
                    if (autoRetryCount < 2) {
                        autoRetryCount++
                        lastAutoRetryMs = now
                        showToast("偵測到畫面卡住，自動重新整理中…", 2600L)
                        binding.loadingChannelTitle.text = "${ch.number} ${ch.name}"
                        binding.loadingOverlay.visibility = View.VISIBLE
                        scheduleLoadingTimeout()
                        webViewHelper.loadUrl(ch.url)
                    } else {
                        showToast("此頻道串流不穩，可按 OK 重新整理或切換他台", 3500L)
                    }
                }
            }
        )
        webViewHelper.setup()
    }

    private fun setupDrawer() {
        drawerAdapter = ChannelDrawerAdapter(
            context = this,
            onChannelClicked = { channel ->
                closeDrawer()
                playChannel(channel, showOsd = true)
            },
            onChannelFocused = {
                resetDrawerAutoCloseTimer()
            }
        )
        binding.channelRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = drawerAdapter
            itemAnimator = null
        }
        refreshDrawerData()
    }

    private fun setupAdminModal() {
        binding.btnAdminLoginMode.setOnClickListener {
            enterLoginCursorMode()
        }
        binding.btnAdminSyncNow.setOnClickListener {
            syncRemoteChannelsManually()
        }
        binding.btnAdminReload.setOnClickListener {
            closeAdminModal()
            reloadCurrentChannel()
        }
        binding.btnAdminClose.setOnClickListener {
            closeAdminModal()
        }
    }

    private fun startAdminServer() {
        adminServer = AdminHttpServer.startWithFallback(
            context = this,
            repository = repository,
            callbacks = object : AdminHttpServer.Callbacks {
                override fun getCurrentChannelId(): String? = currentChannel?.id

                override fun getCurrentWebUrl(): String = webViewHelper.currentUrl

                override fun onConfigUpdated(channels: List<Channel>) {
                    runOnUiThread {
                        refreshDrawerData()
                        val playingId = currentChannel?.id
                        val updatedMatch = channels.firstOrNull { it.id == playingId }
                        if (updatedMatch != null) {
                            val urlChanged = updatedMatch.url != currentChannel?.url
                            currentChannel = updatedMatch
                            if (urlChanged) {
                                playChannel(updatedMatch, showOsd = true)
                            }
                        } else if (channels.isNotEmpty()) {
                            playChannel(channels.first(), showOsd = true)
                        }
                        if (uiMode == UiMode.ADMIN_MODAL) {
                            binding.adminSyncStatusText.text = "目前頻道數：${channels.size} 台"
                        }
                        showToast("頻道清單已更新（共 ${channels.size} 台）", 2600L)
                    }
                }

                override fun onPlayChannelRequested(channel: Channel) {
                    runOnUiThread {
                        if (uiMode == UiMode.ADMIN_MODAL) {
                            closeAdminModal()
                        } else if (uiMode == UiMode.DRAWER_OPEN) {
                            closeDrawer()
                        }
                        playChannel(channel, showOsd = true)
                    }
                }
            }
        )
    }

    private fun triggerBackgroundRemoteSync() {
        val remoteUrl = repository.getRemoteJsonUrl()
        if (!repository.isAutoSyncOnStart() || remoteUrl.isBlank()) return

        lifecycleScope.launch {
            when (val result = RemoteConfigSyncer.fetchSync(remoteUrl)) {
                is SyncResult.Success -> {
                    repository.saveChannels(result.channels)
                    refreshDrawerData()
                    val currentId = currentChannel?.id
                    val updatedCurrent = result.channels.firstOrNull { it.id == currentId }
                    if (updatedCurrent != null && updatedCurrent.url != currentChannel?.url) {
                        playChannel(updatedCurrent, showOsd = true)
                    } else if (updatedCurrent != null) {
                        currentChannel = updatedCurrent
                    }
                }
                is SyncResult.Failure -> Unit
            }
        }
    }

    private fun syncRemoteChannelsManually() {
        val remoteUrl = repository.getRemoteJsonUrl()
        if (remoteUrl.isBlank()) {
            binding.adminSyncStatusText.text = getString(R.string.sync_no_url)
            showToast(getString(R.string.sync_no_url), 3500L)
            return
        }
        binding.adminSyncStatusText.text = getString(R.string.sync_in_progress)
        lifecycleScope.launch {
            when (val result = RemoteConfigSyncer.fetchSync(remoteUrl)) {
                is SyncResult.Success -> {
                    repository.saveChannels(result.channels)
                    refreshDrawerData()
                    val msg = getString(R.string.sync_success, result.channels.size)
                    binding.adminSyncStatusText.text = msg
                    showToast(msg, 3000L)
                }
                is SyncResult.Failure -> {
                    val msg = getString(R.string.sync_failed, result.message)
                    binding.adminSyncStatusText.text = msg
                    showToast(msg, 3500L)
                }
            }
        }
    }

    private fun refreshDrawerData() {
        val channels = repository.getChannels()
        drawerAdapter.submitList(channels, currentChannel?.id)
        binding.drawerCountText.text = "共 ${channels.size} 台"
    }

    private fun playChannel(channel: Channel, showOsd: Boolean) {
        if (currentChannel?.id != channel.id) {
            autoRetryChannelId = channel.id
            autoRetryCount = 0
            okReloadThrottle.reset()
        }
        currentChannel = channel
        repository.setLastChannelId(channel.id)
        drawerAdapter.setCurrentChannelId(channel.id)

        binding.loadingChannelTitle.text = "${channel.number} ${channel.name}"
        binding.loadingStatusText.text = getString(R.string.loading_channel)
        binding.loadingOverlay.visibility = View.VISIBLE
        scheduleLoadingTimeout()

        if (showOsd) {
            showChannelOsd(channel)
        }
        webViewHelper.setAutoFullscreenMode(true)
        webViewHelper.loadUrl(channel.url)
    }

    private fun reloadCurrentChannel(respectCooldown: Boolean = false) {
        if (respectCooldown && !okReloadThrottle.tryAcquire(SystemClock.elapsedRealtime())) {
            showToast(getString(R.string.reload_cooldown_hint), 1500L)
            return
        }
        val ch = currentChannel
        if (ch != null) {
            binding.loadingChannelTitle.text = "${ch.number} ${ch.name}"
            binding.loadingOverlay.visibility = View.VISIBLE
            scheduleLoadingTimeout()
            showChannelOsd(ch)
        }
        showToast(getString(R.string.reloading_channel), 2000L)
        webViewHelper.reload()
    }

    private fun showChannelOsd(channel: Channel) {
        handler.removeCallbacks(hideOsdRunnable)
        binding.osdChannelNumber.text = channel.number.toString()
        binding.osdChannelName.text = channel.name
        drawerAdapter.bindLogoOrBadge(binding.osdLogoImage, binding.osdBadgeText, channel)

        binding.channelOsd.alpha = 1f
        binding.channelOsd.visibility = View.VISIBLE
        handler.postDelayed(hideOsdRunnable, 3600L)
    }

    private fun showToast(message: String, durationMs: Long = 2600L) {
        handler.removeCallbacks(hideToastRunnable)
        binding.toastBanner.text = message
        binding.toastBanner.visibility = View.VISIBLE
        handler.postDelayed(hideToastRunnable, durationMs)
    }

    private fun scheduleLoadingTimeout() {
        handler.removeCallbacks(hideLoadingTimeoutRunnable)
        handler.postDelayed(hideLoadingTimeoutRunnable, 7500L)
    }

    private fun openDrawer() {
        if (uiMode == UiMode.ADMIN_MODAL || uiMode == UiMode.LOGIN_CURSOR) return
        clearNumericInput()
        refreshDrawerData()
        uiMode = UiMode.DRAWER_OPEN

        binding.channelDrawer.visibility = View.VISIBLE
        binding.channelDrawer.translationX = -binding.channelDrawer.width.toFloat().let {
            if (it == 0f) -420f else it
        }
        binding.channelDrawer.animate()
            .translationX(0f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val channels = drawerAdapter.getChannels()
        val currentIdx = channels.indexOfFirst { it.id == currentChannel?.id }.coerceAtLeast(0)
        binding.channelRecyclerView.scrollToPosition(currentIdx)
        binding.channelRecyclerView.post {
            val holder = binding.channelRecyclerView.findViewHolderForAdapterPosition(currentIdx)
            holder?.itemView?.requestFocus()
        }
        resetDrawerAutoCloseTimer()
    }

    private fun closeDrawer() {
        handler.removeCallbacks(autoCloseDrawerRunnable)
        if (uiMode == UiMode.DRAWER_OPEN) {
            uiMode = UiMode.WATCHING
        }
        binding.channelDrawer.animate()
            .translationX(-420f)
            .setDuration(180L)
            .withEndAction {
                binding.channelDrawer.visibility = View.GONE
                binding.channelDrawer.translationX = 0f
            }
            .start()
    }

    private fun resetDrawerAutoCloseTimer() {
        handler.removeCallbacks(autoCloseDrawerRunnable)
        handler.postDelayed(autoCloseDrawerRunnable, 8500L)
    }

    private fun openAdminModal() {
        clearNumericInput()
        if (uiMode == UiMode.DRAWER_OPEN) {
            binding.channelDrawer.visibility = View.GONE
            handler.removeCallbacks(autoCloseDrawerRunnable)
        }
        uiMode = UiMode.ADMIN_MODAL

        val ip = QrCodeGenerator.getLocalIpv4Address()
        val port = adminServer?.listeningPort ?: AdminHttpServer.DEFAULT_PORT
        serverUrl = if (ip != null) "http://$ip:$port" else "尚未連上 Wi-Fi / 區網"
        binding.adminServerUrlText.text = serverUrl
        binding.adminSyncStatusText.text = "目前頻道數：${repository.getChannels().size} 台"

        if (ip != null) {
            binding.adminQrImage.setImageBitmap(QrCodeGenerator.generate(serverUrl, 480))
        } else {
            binding.adminQrImage.setImageBitmap(null)
        }

        binding.adminOverlay.visibility = View.VISIBLE
        binding.btnAdminLoginMode.post {
            binding.btnAdminLoginMode.requestFocus()
        }
    }

    private fun closeAdminModal() {
        binding.adminOverlay.visibility = View.GONE
        if (uiMode == UiMode.ADMIN_MODAL) {
            uiMode = UiMode.WATCHING
        }
        webViewHelper.setWebViewFocusable(false)
    }

    private fun enterLoginCursorMode() {
        binding.adminOverlay.visibility = View.GONE
        uiMode = UiMode.LOGIN_CURSOR
        binding.virtualCursor.visibility = View.VISIBLE
        binding.loginModeBanner.visibility = View.VISIBLE
        binding.loadingOverlay.visibility = View.GONE
        binding.virtualCursor.post {
            binding.virtualCursor.resetToCenter()
        }
        webViewHelper.setAutoFullscreenMode(false)
    }

    private fun exitLoginCursorMode() {
        binding.virtualCursor.visibility = View.GONE
        binding.loginModeBanner.visibility = View.GONE
        uiMode = UiMode.WATCHING
        webViewHelper.flushCookies()
        webViewHelper.setAutoFullscreenMode(true)
        currentChannel?.let { playChannel(it, showOsd = true) } ?: webViewHelper.reload()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (uiMode == UiMode.LOGIN_CURSOR) {
            return handleLoginModeKey(event) || super.dispatchKeyEvent(event)
        }

        if (uiMode == UiMode.ADMIN_MODAL) {
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                closeAdminModal()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }

        if (keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_INFO
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                openAdminModal()
            }
            return true
        }

        if (isOkKey(keyCode)) {
            return handleOkKey(event)
        }

        if (uiMode == UiMode.DRAWER_OPEN) {
            return handleDrawerKey(event) || super.dispatchKeyEvent(event)
        }

        return handleWatchingKey(event) || super.dispatchKeyEvent(event)
    }

    private fun handleOkKey(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerDownHandledLongPress = false
                    handler.removeCallbacks(longPressAdminRunnable)
                    handler.postDelayed(longPressAdminRunnable, 2000L)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressAdminRunnable)
                if (centerDownHandledLongPress) {
                    centerDownHandledLongPress = false
                    return true
                }
                if (uiMode == UiMode.DRAWER_OPEN) {
                    val focused = currentFocus
                    if (focused != null && focused.parent === binding.channelRecyclerView) {
                        focused.performClick()
                    }
                    return true
                }
                if (digitBuffer.isNotEmpty()) {
                    commitPendingDigits()
                } else {
                    reloadCurrentChannel(respectCooldown = true)
                }
                return true
            }
        }
        return true
    }

    private fun handleWatchingKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return isInterceptedWatchingKey(event.keyCode)
        }

        val digit = keyCodeToDigit(event.keyCode)
        if (digit != null) {
            appendDigit(digit)
            return true
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                clearNumericInput()
                repository.getPreviousChannel(currentChannel?.id)?.let { playChannel(it, showOsd = true) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                clearNumericInput()
                repository.getNextChannel(currentChannel?.id)?.let { playChannel(it, showOsd = true) }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                openDrawer()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (digitBuffer.isNotEmpty()) {
                    clearNumericInput()
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs <= 2500L) {
                    finish()
                } else {
                    lastBackPressMs = now
                    showToast(getString(R.string.double_back_exit_hint), 2500L)
                }
                true
            }
            else -> false
        }
    }

    private fun handleDrawerKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_BACK
        }
        resetDrawerAutoCloseTimer()

        val digit = keyCodeToDigit(event.keyCode)
        if (digit != null) {
            closeDrawer()
            appendDigit(digit)
            return true
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK -> {
                closeDrawer()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val count = drawerAdapter.itemCount
                if (count > 1 && isFirstDrawerItemFocused()) {
                    focusDrawerPosition(count - 1)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val count = drawerAdapter.itemCount
                if (count > 1 && isLastDrawerItemFocused(count)) {
                    focusDrawerPosition(0)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun handleLoginModeKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                exitLoginCursorMode()
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    binding.virtualCursor.moveBy(0, -1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    binding.virtualCursor.moveBy(0, 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.virtualCursor.moveBy(-1, 0)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    binding.virtualCursor.moveBy(1, 0)
                    return true
                }
            }
        }

        if (isOkKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                binding.virtualCursor.performClick(binding.webView)
            }
            return true
        }
        return false
    }

    private fun isFirstDrawerItemFocused(): Boolean {
        val focused = currentFocus ?: return false
        val pos = binding.channelRecyclerView.getChildAdapterPosition(focused)
        return pos == 0
    }

    private fun isLastDrawerItemFocused(count: Int): Boolean {
        val focused = currentFocus ?: return false
        val pos = binding.channelRecyclerView.getChildAdapterPosition(focused)
        return pos == count - 1
    }

    private fun focusDrawerPosition(position: Int) {
        binding.channelRecyclerView.scrollToPosition(position)
        binding.channelRecyclerView.post {
            binding.channelRecyclerView.findViewHolderForAdapterPosition(position)
                ?.itemView?.requestFocus()
        }
    }

    private fun appendDigit(digit: Int) {
        handler.removeCallbacks(commitDigitsRunnable)
        if (digitBuffer.length >= 3) {
            digitBuffer.clear()
        }
        digitBuffer.append(digit)
        val num = digitBuffer.toString().toIntOrNull()
        val matched = if (num != null) repository.findByNumber(num) else null

        binding.numberOsdDigits.text = digitBuffer.toString()
        binding.numberOsdPreview.text = matched?.name ?: "輸入台號中…"
        binding.numberOsd.visibility = View.VISIBLE

        handler.postDelayed(commitDigitsRunnable, 1600L)
    }

    private fun commitPendingDigits() {
        handler.removeCallbacks(commitDigitsRunnable)
        val raw = digitBuffer.toString()
        digitBuffer.clear()
        binding.numberOsd.visibility = View.GONE
        val number = raw.toIntOrNull() ?: return
        val target = repository.findByNumber(number)
        if (target != null) {
            playChannel(target, showOsd = true)
        } else {
            showToast(getString(R.string.channel_not_found, number), 2200L)
        }
    }

    private fun clearNumericInput() {
        handler.removeCallbacks(commitDigitsRunnable)
        digitBuffer.clear()
        binding.numberOsd.visibility = View.GONE
    }

    private fun isOkKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    private fun isInterceptedWatchingKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
            keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_BACK ||
            keyCodeToDigit(keyCode) != null
    }

    private fun keyCodeToDigit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> null
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
