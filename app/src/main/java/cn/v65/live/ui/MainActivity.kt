package cn.v65.live.ui

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import cn.v65.live.data.*
import cn.v65.live.playback.*
import cn.v65.live.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: Preferences
    private lateinit var repository: ChannelRepository
    private val channelsAdapter = ChannelAdapter { select(it) }
    private val programsAdapter = ProgramAdapter()
    private var channels = emptyList<Channel>()
    private var selected: Channel? = null
    private var schedule: Schedule? = null
    private var scheduleChannelId: String? = null
    private var catalog: Catalog? = null
    private var filter = 0
    private var scheduleJob: Job? = null
    private var catalogJob: Job? = null
    private var lastEpgRefresh = 0L
    private lateinit var resolver: PlaybackResolver
    private lateinit var player: PlayerController
    private var playbackJob: Job? = null
    private var activeSource: PlaybackResolution.NativeSource? = null
    private var fullScreen = false
    private var playbackStartedAt = 0L
    private var autoRefreshed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        preferences = Preferences(this)
        title = "客厅直播 0.4.0 · 直播源"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val client = SourceClient()
        repository = ChannelRepository(this, client)
        resolver = PlaybackResolver(this, client)
        player = PlayerController(this, binding.playerView) { event ->
            val channel = selected ?: return@PlayerController
            when (event) {
                is PlayerController.Event.Status -> {
                    if (event.text != "直播源播放中") showMessage(event.text, true)
                }
                PlayerController.Event.FirstFrame -> {
                    binding.playerMessage.isVisible = false
                    binding.playbackMode.text = "${activeSource?.sourceName ?: "直播源"} · ${decoderName()}"
                    preferences.record(channel, "原生首帧回调", "${decoderName()} · 耗时 ${System.currentTimeMillis() - playbackStartedAt}ms；实际画面仍需确认")
                }
                is PlayerController.Event.Diagnostic -> preferences.record(channel, "原生诊断", event.detail)
                is PlayerController.Event.Failure -> {
                    preferences.record(channel, "原生播放失败", event.reason)
                    if (event.refreshable && !autoRefreshed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        autoRefreshed = true
                        startPlayback(channel, forceRefresh = true)
                    } else {
                        player.stop()
                        showMessage(event.reason + "\n可按重试、切换解码方式或选择其它频道", false)
                        binding.playbackMode.text = "直播源暂不可用"
                    }
                }
            }
        }
        binding.channelList.apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = channelsAdapter; itemAnimator = null }
        binding.programList.apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = programsAdapter; itemAnimator = null }
        binding.allFilter.setOnClickListener { setFilter(0) }
        binding.favoriteFilter.setOnClickListener { setFilter(1) }
        binding.recentFilter.setOnClickListener { setFilter(2) }
        binding.refreshButton.setOnClickListener { fetchChannels(forceRefresh = true) }
        binding.diagnosticsButton.setOnClickListener { showDiagnostics() }
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }
        binding.saveButton.setOnClickListener { selected?.let { preferences.toggleFavorite(it.id); renderChannels(); updateFavoriteButton() } }
        binding.retryButton.setOnClickListener { selected?.let { autoRefreshed = false; startPlayback(it) } ?: fetchChannels() }
        updateDecoderButton()
        binding.webButton.setOnClickListener {
            preferences.softwareDecoder = !preferences.softwareDecoder
            updateDecoderButton()
            selected?.let { channel ->
                autoRefreshed = false
                val source = activeSource
                if (source != null && (source.expiresAt ?: Long.MAX_VALUE) > System.currentTimeMillis() + 15_000 && System.currentTimeMillis() - source.resolvedAt < 45_000) {
                    player.stop()
                    playbackStartedAt = System.currentTimeMillis()
                    showMessage("正在切换${decoderName()}解码", true)
                    player.play(source, preferences.softwareDecoder)
                } else startPlayback(channel)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreen) {
                    if (binding.playerControls.isVisible) binding.playerControls.isVisible = false else toggleFullscreen()
                    return
                }
                AlertDialog.Builder(this@MainActivity).setTitle("退出客厅直播？")
                    .setNegativeButton("继续观看", null).setPositiveButton("退出") { _, _ -> finish() }.show()
            }
        })
        setActionsEnabled(false)
        setFilter(0)
        lifecycleScope.launch { resolver.refreshPublicSources() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    binding.clock.text = BeijingTime.clock()
                    renderSchedule(false)
                    if (selected != null && (schedule?.date != BeijingTime.dateKey() || System.currentTimeMillis() - lastEpgRefresh > 300_000)) fetchSchedule(selected!!)
                    delay(15_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) fetchChannels()
        else {
            val last = channels.firstOrNull { it.id == preferences.lastChannel } ?: selected
            if (last != null) {
                select(last)
            }
        }
    }

    override fun onStop() {
        playbackJob?.cancel()
        resolver.close()
        player.stop()
        activeSource = null
        scheduleJob?.cancel()
        super.onStop()
    }

    private fun fetchChannels(forceRefresh: Boolean = false) {
        catalogJob?.cancel()
        binding.catalogStatus.text = "正在更新官方频道"
        binding.refreshButton.isEnabled = false
        catalogJob = lifecycleScope.launch {
            try {
                if (channels.isEmpty()) {
                    val cached = repository.cachedChannels()
                    catalog = cached
                    channels = cached.channels
                    binding.catalogStatus.text = "${cached.origin} · ${channels.size} 个频道 · 后台更新中"
                    setActionsEnabled(true)
                    val first = channels.firstOrNull { it.id == preferences.lastChannel } ?: channels.first()
                    select(first)
                }
                val result = repository.channels(forceRefresh)
                catalog = result
                channels = result.channels
                binding.catalogStatus.text = "${result.origin} · ${channels.size} 个频道" + if (result.warning != null) "\n${result.warning}" else ""
                renderChannels()
                setActionsEnabled(true)
                val previous = selected
                if (previous == null) {
                    val first = channels.firstOrNull { it.id == preferences.lastChannel } ?: channels.first()
                    select(first)
                    binding.channelList.post { binding.channelList.findViewHolderForAdapterPosition(channels.indexOf(first))?.itemView?.requestFocus() ?: binding.allFilter.requestFocus() }
                } else {
                    channels.firstOrNull { it.id == previous.id }?.let { selected = it; updateFavoriteButton(); fetchSchedule(it) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                binding.catalogStatus.text = "频道获取失败，请检查网络后刷新"
                showMessage("暂时无法获取频道", false)
                binding.refreshButton.requestFocus()
            } finally { binding.refreshButton.isEnabled = true }
        }
    }

    private fun setFilter(value: Int) {
        filter = value
        binding.allFilter.isSelected = value == 0
        binding.favoriteFilter.isSelected = value == 1
        binding.recentFilter.isSelected = value == 2
        renderChannels()
    }

    private fun visibleChannels(): List<Channel> = when (filter) {
        1 -> channels.filter { it.id in preferences.favorites }
        2 -> preferences.recents.mapNotNull { id -> channels.firstOrNull { it.id == id } }
        else -> channels
    }

    private fun renderChannels() {
        val items = visibleChannels()
        channelsAdapter.submitList(items.map { ChannelRow(it, channels.indexOf(it) + 1, it.id == selected?.id, it.id in preferences.favorites) })
        binding.channelEmpty.isVisible = items.isEmpty()
        binding.channelEmpty.text = when (filter) { 1 -> "还没有收藏的频道"; 2 -> "还没有最近打开的频道"; else -> "暂无频道" }
    }

    private fun select(channel: Channel) {
        preferences.opened(channel.id)
        prepareChannel(channel)
        autoRefreshed = false
        startPlayback(channel)
    }

    private fun prepareChannel(channel: Channel) {
        selected = channel
        binding.playerTitle.text = channel.name
        binding.playbackMode.text = "直播源 · ${decoderName()}"
        binding.retryButton.isEnabled = true
        renderChannels(); updateFavoriteButton(); fetchSchedule(channel)
    }

    private fun fetchSchedule(channel: Channel) {
        scheduleJob?.cancel()
        lastEpgRefresh = System.currentTimeMillis()
        if (schedule?.date != BeijingTime.dateKey() || scheduleChannelId != channel.id) {
            schedule = null
            renderSchedule(false)
        }
        scheduleChannelId = channel.id
        scheduleJob = lifecycleScope.launch {
            val result = repository.schedule(channel)
            if (selected?.id != channel.id) return@launch
            schedule = result
            renderSchedule(true)
        }
    }

    private fun renderSchedule(scrollToCurrent: Boolean) {
        val data = schedule
        val now = System.currentTimeMillis()
        val programs = data?.programs.orEmpty()
        val current = programs.firstOrNull { it.isCurrent(now) }
        val next = programs.firstOrNull { it.startsAt > now }
        binding.currentProgram.text = current?.name ?: if (data == null) "正在获取节目单" else "当前节目暂无信息"
        binding.nextProgram.text = next?.let { "接下来 ${BeijingTime.clock(it.startsAt)}  ${it.name}" }.orEmpty()
        binding.scheduleStatus.text = BeijingTime.dateLabel() + " · 北京时间" + (data?.warning?.let { "\n$it" } ?: if (data != null && programs.isEmpty()) "\n节目单暂无数据" else "")
        binding.programProgress.progress = current?.let { (((now - it.startsAt) * 1000) / (it.endsAt - it.startsAt).coerceAtLeast(1)).toInt().coerceIn(0, 1000) } ?: 0
        programsAdapter.submitList(programs.map { ProgramRow(it, it.isCurrent(now)) }) {
            if (scrollToCurrent) {
                val index = programs.indexOfFirst { it.isCurrent(now) }
                if (index >= 0) (binding.programList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
            }
        }
    }

    private fun startPlayback(channel: Channel, forceRefresh: Boolean = false) {
        playbackJob?.cancel()
        resolver.close()
        player.stop()
        activeSource = null
        playbackStartedAt = System.currentTimeMillis()
        binding.playbackMode.text = "直播源 · ${decoderName()}"
        showMessage("正在获取 ${channel.name} 直播源", true)
        playbackJob = lifecycleScope.launch {
            val result = resolver.resolve(channel, forceRefresh)
            if (selected?.id != channel.id || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            when (result) {
                is PlaybackResolution.NativeSource -> {
                    activeSource = result
                    binding.playbackMode.text = "${result.sourceName} · ${decoderName()}"
                    preferences.record(channel, "直播源解析完成", "${result.sourceName} · 耗时 ${System.currentTimeMillis() - playbackStartedAt}ms · ${result.mimeType}")
                    showMessage("直播源已取得，正在解码", true)
                    player.play(result, preferences.softwareDecoder)
                }
                is PlaybackResolution.Unavailable -> sourceUnavailable(channel, result.reason)
                is PlaybackResolution.OfficialPage -> sourceUnavailable(channel, result.reason)
            }
        }
    }

    private fun sourceUnavailable(channel: Channel, reason: String) {
        binding.playbackMode.text = "直播源暂不可用"
        preferences.record(channel, "直播源不可用", reason)
        showMessage(reason, false)
    }

    private fun decoderName() = if (preferences.softwareDecoder) "软件优先" else "硬件优先"
    private fun updateDecoderButton() { binding.webButton.text = "解码：${if (preferences.softwareDecoder) "软件" else "硬件"}" }

    private fun toggleFullscreen() {
        val container = binding.playerContainer
        (container.parent as? ViewGroup)?.removeView(container)
        fullScreen = !fullScreen
        if (fullScreen) {
            binding.fullscreenHost.addView(container, FrameLayout.LayoutParams(-1, -1))
            binding.fullscreenHost.isVisible = true
            binding.contentPage.isVisible = false
            binding.playerControls.isVisible = false
        } else {
            binding.playerSlot.addView(container, FrameLayout.LayoutParams(-1, -1))
            binding.contentPage.isVisible = true
            binding.fullscreenHost.isVisible = false
            binding.playerControls.isVisible = true
            binding.fullscreenButton.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullScreen && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && channels.isNotEmpty()) {
                val index = channels.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                select(channels[(index + step + channels.size) % channels.size])
            }
            return true
        }
        if (fullScreen && event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_MENU ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && !binding.playerControls.isVisible)) {
            binding.playerControls.isVisible = !binding.playerControls.isVisible
            if (binding.playerControls.isVisible) binding.retryButton.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMessage(text: String, busy: Boolean) {
        binding.playerMessage.isVisible = true
        binding.playerStatus.text = text
        binding.loading.isVisible = busy
    }

    private fun updateFavoriteButton() { binding.saveButton.text = if (selected?.id in preferences.favorites) "已收藏" else "收藏" }
    private fun setActionsEnabled(enabled: Boolean) {
        listOf(binding.fullscreenButton, binding.saveButton, binding.retryButton, binding.webButton).forEach { it.isEnabled = enabled }
    }

    private fun showDiagnostics() {
        val webVersion = if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName.orEmpty() else "系统内置 WebView"
        val text = "设备：${Build.MANUFACTURER} ${Build.MODEL}\nAndroid API：${Build.VERSION.SDK_INT}\nWebView：$webVersion\n" +
            "频道：${channels.size}\n数据来源：${catalog?.origin ?: "未加载"}\n更新时间：${catalog?.updatedAt?.let { BeijingTime.timestamp(it) } ?: "未知"}\n\n" + preferences.diagnostics()
        val view = TextView(this).apply { this.text = text; textSize = 14f; setTextColor(0xFFF2F5F4.toInt()); setPadding(24, 18, 24, 18) }
        AlertDialog.Builder(this).setTitle("本机诊断").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("关闭", null).show()
    }
}
