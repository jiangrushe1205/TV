package cn.v65.live.ui

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.v65.live.BuildConfig
import cn.v65.live.data.Channel
import cn.v65.live.data.ChannelRepository
import cn.v65.live.data.Preferences
import cn.v65.live.data.SourceClient
import cn.v65.live.databinding.ActivityOfficialWebBinding
import cn.v65.live.playback.OfficialSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/** One official WebView, with TV navigation rendered above every web fullscreen view. */
class OfficialWebActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOfficialWebBinding
    private lateinit var preferences: Preferences
    private lateinit var adapter: ChannelAdapter
    private val state = WebTvState()
    private val handler = Handler(Looper.getMainLooper())
    private val interceptedKeys = mutableSetOf<Int>()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var webDead = false
    private var foreground = false
    private var pageStartedAt = 0L
    private var previousMediaTime = -1.0
    private var videoConfirmed = false
    private var pageFailed = false
    private var userPaused = false
    private val activityStartedAt = SystemClock.elapsedRealtime()
    private var firstOpen = true
    private var exitDialog: AlertDialog? = null
    private val hideToolbar = Runnable {
        if (state.overlay == WebTvState.Overlay.TOOLBAR) setOverlay(WebTvState.Overlay.NONE)
    }
    private val hideChannelToast = Runnable { binding.channelToast.isVisible = false }
    private val sampleVideo = object : Runnable {
        override fun run() {
            if (webDead || !foreground || pageStartedAt == 0L) return
            val token = state.token
            installPresentation(token)
            binding.webView.evaluateJavascript(OfficialPagePresentation.sample) { value ->
                if (!foreground || webDead || !state.isCurrent(token)) return@evaluateJavascript
                runCatching {
                    val data = JSONObject(JSONTokener(value).nextValue().toString())
                    if (data.optString("pid") != token.channelId || data.optLong("generation", -1) != token.generation) return@runCatching
                    if (data.optBoolean("fullscreen") && data.optBoolean("fitted") &&
                        (data.optBoolean("freshMedia", true) || data.optBoolean("actionRequired"))) binding.loadingCover.isVisible = false
                    val current = data.optDouble("time", -1.0)
                    if (!videoConfirmed && data.optBoolean("freshMedia", true) && previousMediaTime >= 0 && current > previousMediaTime &&
                        data.optInt("ready") >= 2 && data.optInt("width") > 0 && !data.optBoolean("paused", true)) {
                        videoConfirmed = true
                        if (!pageFailed) binding.statusPanel.isVisible = false
                        record("媒体开始推进", "${elapsed()} ms；需单独核对电视画面、声音")
                    }
                    if (!data.optBoolean("online", true)) showStatus("网络已断开，请检查电视网络后刷新")
                    else if (data.optInt("error") > 0) showStatus("官网媒体播放失败（${data.optInt("error")}），可刷新或进入网页操作")
                    previousMediaTime = current
                }
            }
            if (!pageFailed && !videoConfirmed && !userPaused && elapsed() >= 20_000) {
                showStatus("${state.current.name} · 仍在加载；如官网要求播放、登录或验证，请进入网页操作")
            }
            handler.postDelayed(this, if (elapsed() < 2_000) 250 else if (elapsed() < 20_000) 500 else 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Preferences(this)
        binding = ActivityOfficialWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreen()
        adapter = ChannelAdapter(View.NO_ID) { openChannel(it) }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter
        setupButtons()
        setupWebView()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (state.back()) {
                    WebTvState.BackAction.DISMISSED_OVERLAY -> setOverlay(WebTvState.Overlay.NONE)
                    WebTvState.BackAction.CONFIRM_EXIT -> confirmExit()
                }
            }
        })
        val preferred = savedInstanceState?.getString("channel_id")
            ?: intent.getStringExtra("channel_id")?.takeIf(WebTvState::validId)
            ?: preferences.lastChannel
        val repository = ChannelRepository(applicationContext, SourceClient())
        lifecycleScope.launch {
            val cached = try { repository.cachedChannels() } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            state.updateCatalog(cached?.channels.orEmpty(), preferred)
            binding.catalogStatus.text = cached?.origin ?: "内置默认频道"
            openChannel(state.current)
            // Fresh cached data needs no network; expired/missing data refreshes behind playback.
            val updated = repository.channels()
            val oldId = state.current.id
            state.updateCatalog(updated.channels)
            binding.catalogStatus.text = listOfNotNull(updated.origin, updated.warning).joinToString(" · ")
            if (oldId != state.current.id) openChannel(state.current)
            else if (state.overlay == WebTvState.Overlay.CHANNELS) renderChannels(false)
        }
    }

    private fun setupButtons() {
        binding.reloadButton.setOnClickListener { retry() }
        binding.statusRetry.setOnClickListener { retry() }
        binding.pointerButton.setOnClickListener { setOverlay(WebTvState.Overlay.POINTER) }
        binding.statusOperate.setOnClickListener { setOverlay(WebTvState.Overlay.POINTER) }
        binding.exitButton.setOnClickListener { confirmExit() }
        binding.favoriteButton.setOnClickListener {
            preferences.toggleFavorite(state.current.id)
            updateTitle()
            showChannelToast(if (state.current.id in preferences.favorites) "已收藏 ${state.current.name}" else "已取消收藏 ${state.current.name}")
            armToolbarTimer()
        }
        binding.mediaButton.setOnClickListener {
            if (!webDead) {
                val token = state.token
                binding.webView.evaluateJavascript(OfficialPagePresentation.toggleMedia) { result ->
                    if (state.isCurrent(token) && foreground) {
                        userPaused = result == "true"
                        binding.webView.evaluateJavascript(OfficialPagePresentation.mediaPolicy(foreground, userPaused), null)
                    }
                }
            }
            armToolbarTimer()
        }
        listOf(binding.cctvTab, binding.satelliteTab, binding.favoritesTab).forEachIndexed { index, button ->
            button.setOnClickListener { state.group = WebTvState.Group.entries[index]; renderChannels(true) }
        }
        listOf(binding.mediaButton, binding.reloadButton, binding.favoriteButton, binding.pointerButton, binding.exitButton).forEach {
            it.nextFocusDownId = binding.statusRetry.id
        }
        binding.statusRetry.nextFocusUpId = binding.reloadButton.id
        binding.statusOperate.nextFocusUpId = binding.pointerButton.id
        binding.webView.onToolbar = { setOverlay(WebTvState.Overlay.TOOLBAR) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getStringExtra("channel_id")?.takeIf(WebTvState::validId) ?: return
        if (pageStartedAt != 0L) openChannel(state.channels.firstOrNull { it.id == id } ?: WebTvState.DEFAULT_CHANNEL)
    }

    private fun setupWebView() {
        OfficialSite.configure(binding.webView, probe = false)
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null || !foreground || state.overlay == WebTvState.Overlay.POINTER) {
                    callback.onCustomViewHidden(); return
                }
                customView = view
                customCallback = callback
                binding.customHost.addView(view, FrameLayout.LayoutParams(-1, -1))
                binding.customHost.isVisible = true
                binding.webView.visibility = View.INVISIBLE
                // The host is below all native menus in the root FrameLayout.
                restoreFocus()
            }
            override fun onHideCustomView() { hideCustomView() }
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (foreground && OfficialSite.permitsNavigation(request.origin.toString()) && allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }
            }
        }
        binding.webView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blockNavigation(url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && blockNavigation(request.url.toString())
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (expectedUrl(url)) record("页面开始", "${elapsed()} ms")
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!expectedUrl(url) || webDead) return
                record("页面加载完成", "${elapsed()} ms；官网验证和媒体加载可能仍在进行")
                installPresentation(state.token)
                if (!foreground) view.evaluateJavascript(OfficialPagePresentation.pauseMedia, null)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && expectedUrl(request.url.toString())) {
                    pageFailed = true
                    showStatus("官方网页加载失败（${error.errorCode}），请检查网络后刷新")
                    record("页面加载失败", "代码 ${error.errorCode}，${elapsed()} ms")
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && expectedUrl(request.url.toString())) {
                    pageFailed = true
                    showStatus("官方网页返回 HTTP ${response.statusCode}，可刷新重试")
                }
            }
            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                if (expectedUrl(view.url)) {
                    pageFailed = true
                    showStatus("HTTPS 证书验证失败，已停止连接；请检查电视日期和网络")
                    record("证书错误", "已拒绝不安全连接")
                }
            }
            @androidx.annotation.RequiresApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                webDead = true
                state.invalidateCallbacks()
                handler.removeCallbacks(sampleVideo)
                hideCustomView()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                record("渲染器退出", "崩溃=${detail.didCrash()}；刷新可重新创建播放器")
                showStatus("系统网页渲染器已退出，请按菜单键选择刷新以重新创建播放器")
                setOverlay(WebTvState.Overlay.TOOLBAR)
                binding.reloadButton.requestFocus()
                return true
            }
        }
    }

    private fun expectedUrl(url: String?): Boolean = url != null && OfficialSite.permitsNavigation(url) &&
        Uri.parse(url).getQueryParameter("pid") == state.current.id

    private fun blockNavigation(url: String): Boolean {
        if (OfficialSite.permitsNavigation(url)) return false
        showStatus("已停止离开央视频官方域名的跳转")
        return true
    }

    private fun installPresentation(token: WebTvState.PageToken) {
        if (webDead) return
        val script = "if(new URL(location.href).searchParams.get('pid')==='${token.channelId}'){${OfficialPagePresentation.install(token.generation)};${OfficialPagePresentation.enableVideo(state.overlay != WebTvState.Overlay.POINTER)};${OfficialPagePresentation.mediaPolicy(foreground, userPaused)}}"
        binding.webView.evaluateJavascript(script, null)
    }

    private fun openChannel(channel: Channel) {
        val previousId = state.current.id
        val canReusePage = pageStartedAt != 0L && previousId != channel.id
        val token = state.beginPage(channel)
        if (webDead) { preferences.opened(channel.id); recreate(); return }
        hideCustomView()
        binding.webView.evaluateJavascript(OfficialPagePresentation.prepareSwitch(channel.id), null)
        pageStartedAt = SystemClock.elapsedRealtime()
        previousMediaTime = -1.0
        videoConfirmed = false
        pageFailed = false
        userPaused = false
        binding.statusPanel.isVisible = false
        binding.loadingCover.text = "${channel.name} · 正在切换官方直播…"
        binding.loadingCover.isVisible = true
        preferences.opened(channel.id)
        setOverlay(WebTvState.Overlay.NONE)
        showChannelToast("${channel.name} · 正在打开官方直播")
        record("打开频道", "WebView；无需等待原生取源")
        if (firstOpen) {
            firstOpen = false
            record("启动打开", "Activity 创建至打开官网 ${SystemClock.elapsedRealtime() - activityStartedAt} ms")
        }
        if (!canReusePage) loadOfficialPage(channel, token)
        else binding.webView.evaluateJavascript(OfficialPagePresentation.switchChannel(channel)) { result ->
            if (!state.isCurrent(token) || webDead) return@evaluateJavascript
            if (result == "true") {
                record("站内切台", "复用官网页面和播放器资源")
                handler.postDelayed({
                    if (state.isCurrent(token) && !webDead) {
                        binding.webView.evaluateJavascript("new URL(location.href).searchParams.get('pid')") { pid ->
                            if (state.isCurrent(token) && pid != "\"${channel.id}\"") loadOfficialPage(channel, token)
                        }
                    }
                }, 1_500)
            } else loadOfficialPage(channel, token)
        }
        handler.removeCallbacks(sampleVideo)
        if (foreground) handler.postDelayed(sampleVideo, 100)
    }

    private fun loadOfficialPage(channel: Channel, token: WebTvState.PageToken) {
        if (webDead || !state.isCurrent(token)) return
        binding.webView.stopLoading()
        binding.webView.loadUrl(channel.officialUrl)
        record("整页加载", "首次打开、刷新或站内切台不可用")
    }

    private fun retry() {
        if (webDead) recreate() else openChannel(state.current)
    }

    private fun setOverlay(overlay: WebTvState.Overlay) {
        handler.removeCallbacks(hideToolbar)
        if (overlay == WebTvState.Overlay.POINTER) hideCustomView()
        state.overlay = overlay
        binding.channelPanel.isVisible = overlay == WebTvState.Overlay.CHANNELS
        binding.webToolbar.isVisible = overlay == WebTvState.Overlay.TOOLBAR
        binding.pointerHint.isVisible = overlay == WebTvState.Overlay.POINTER
        binding.webView.pointerEnabled = overlay == WebTvState.Overlay.POINTER
        if (overlay == WebTvState.Overlay.POINTER) binding.loadingCover.isVisible = false
        if (!webDead) binding.webView.evaluateJavascript(OfficialPagePresentation.enableVideo(overlay != WebTvState.Overlay.POINTER), null)
        updateTitle()
        when (overlay) {
            WebTvState.Overlay.CHANNELS -> {
                if (state.group != WebTvState.Group.FAVORITES || state.current.id !in preferences.favorites) {
                    state.group = if (state.current.group == "weishi") WebTvState.Group.SATELLITE else WebTvState.Group.CCTV
                }
                renderChannels(true)
            }
            WebTvState.Overlay.TOOLBAR -> { binding.mediaButton.requestFocus(); armToolbarTimer() }
            else -> binding.webView.requestFocus()
        }
        fullscreen()
    }

    private fun restoreFocus() {
        when (state.overlay) {
            WebTvState.Overlay.CHANNELS -> renderChannels(true)
            WebTvState.Overlay.TOOLBAR -> binding.mediaButton.requestFocus()
            else -> binding.webView.requestFocus()
        }
    }

    private fun renderChannels(focus: Boolean) {
        val favorites = preferences.favorites
        val rows = state.visibleChannels(favorites).map { channel ->
            ChannelRow(channel, state.channels.indexOfFirst { it.id == channel.id } + 1, channel.id == state.current.id, channel.id in favorites)
        }
        listOf(binding.cctvTab, binding.satelliteTab, binding.favoritesTab).forEachIndexed { i, tab -> tab.isSelected = state.group.ordinal == i }
        binding.emptyChannels.isVisible = rows.isEmpty()
        adapter.submitList(rows) {
            if (!focus || state.overlay != WebTvState.Overlay.CHANNELS) return@submitList
            if (rows.isEmpty()) { binding.favoritesTab.requestFocus(); return@submitList }
            val position = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
            (binding.channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
            binding.channelList.post {
                if (state.overlay == WebTvState.Overlay.CHANNELS) {
                    binding.channelList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                        ?: binding.channelList.requestFocus()
                }
            }
        }
    }

    private fun updateTitle() {
        binding.webTitle.text = "${state.current.name} · 央视频官方网页 · ${BuildConfig.VERSION_NAME}"
        binding.favoriteButton.text = if (state.current.id in preferences.favorites) "取消收藏" else "收藏"
    }

    private fun showStatus(message: String) {
        binding.webStatus.text = message
        binding.statusPanel.isVisible = true
    }

    private fun showChannelToast(message: String) {
        handler.removeCallbacks(hideChannelToast)
        binding.channelToast.text = message
        binding.channelToast.isVisible = true
        handler.postDelayed(hideChannelToast, 3_000)
    }

    private fun armToolbarTimer() {
        handler.removeCallbacks(hideToolbar)
        if (state.overlay == WebTvState.Overlay.TOOLBAR) handler.postDelayed(hideToolbar, 5_000)
    }

    private fun confirmExit() {
        if (exitDialog?.isShowing == true) return
        handler.removeCallbacks(hideToolbar)
        exitDialog = AlertDialog.Builder(this).setTitle("退出电视直播？")
            .setMessage("退出后停止官网播放")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("继续观看") { _, _ -> setOverlay(WebTvState.Overlay.NONE) }
            .setOnCancelListener { setOverlay(WebTvState.Overlay.NONE) }
            .create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
                dialog.show()
            }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customView = null
        binding.customHost.removeView(view)
        binding.customHost.isVisible = false
        binding.webView.visibility = View.VISIBLE
        val callback = customCallback
        customCallback = null
        callback?.onCustomViewHidden()
        fullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::binding.isInitialized || exitDialog?.isShowing == true) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && interceptedKeys.remove(event.keyCode)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val first = event.repeatCount == 0
        val handled = when {
            event.keyCode == KeyEvent.KEYCODE_MENU -> {
                if (first) setOverlay(WebTvState.Overlay.TOOLBAR)
                true
            }
            state.overlay == WebTvState.Overlay.CHANNELS && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                if (first) {
                    state.nextGroup(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    renderChannels(true)
                }
                true
            }
            state.overlay == WebTvState.Overlay.NONE -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (first) openChannel(state.adjacent(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1))
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (first) setOverlay(WebTvState.Overlay.CHANNELS)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
            else -> false
        }
        if (state.overlay == WebTvState.Overlay.TOOLBAR) armToolbarTimer()
        if (handled) { interceptedKeys.add(event.keyCode); return true }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun fullscreen() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun elapsed() = if (pageStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - pageStartedAt
    private fun record(mode: String, detail: String) {
        Log.i("V65WebTV", "${state.current.name} | $mode | $detail")
        preferences.record(state.current, mode, detail)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("channel_id", state.current.id)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        fullscreen()
        if (!webDead) {
            binding.webView.onResume()
            installPresentation(state.token)
            if (!userPaused) binding.webView.evaluateJavascript(OfficialPagePresentation.resumeMedia, null)
            handler.removeCallbacks(sampleVideo)
            handler.postDelayed(sampleVideo, 1_000)
        }
    }

    override fun onPause() {
        foreground = false
        state.invalidateCallbacks()
        handler.removeCallbacks(sampleVideo)
        handler.removeCallbacks(hideToolbar)
        if (!webDead) {
            binding.webView.evaluateJavascript(OfficialPagePresentation.mediaPolicy(false, userPaused), null)
            binding.webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exitDialog?.dismiss()
        hideCustomView()
        if (!webDead) {
            binding.webView.stopLoading()
            binding.webView.evaluateJavascript(OfficialPagePresentation.pauseMedia, null)
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
            webDead = true
        }
        super.onDestroy()
    }
}
