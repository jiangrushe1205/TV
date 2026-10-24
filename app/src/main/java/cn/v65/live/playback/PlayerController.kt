package cn.v65.live.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import java.net.URI

@UnstableApi
class PlayerController(
    private val context: Context,
    private val view: PlayerView,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data class Status(val text: String) : Event
        data object FirstFrame : Event
        data class Diagnostic(val detail: String) : Event
        data class Failure(val reason: String, val refreshable: Boolean) : Event
    }
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var firstFrame = false
    private var timeoutAction: (() -> Unit)? = null
    private val watchdog = Runnable { timeoutAction?.invoke() }

    fun play(source: PlaybackResolution.NativeSource, softwareDecoder: Boolean) {
        stop()
        firstFrame = false
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.headers)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(7_000)
            .setAllowCrossProtocolRedirects(true)
        val codecSelector = MediaCodecSelector { mime, secure, tunnel ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
            if (softwareDecoder) codecs.sortedBy { if (it.softwareOnly) 0 else 1 } else codecs
        }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(codecSelector)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 20_000, 800, 1_500).build()
        val exo = ExoPlayer.Builder(context, renderers).setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))).build()
        player = exo
        val candidates = (listOf(source.url) + source.alternateUrls).distinct()
        var candidateIndex = 0
        var windowRecoveries = 0
        fun mediaItem(url: String) = MediaItem.Builder().setUri(url).setMimeType(source.mimeType)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build()).build()
        fun beginCandidate() {
            firstFrame = false
            onEvent(Event.Diagnostic("${source.sourceName} · 线路 ${candidateIndex + 1}/${candidates.size} · ${URI(candidates[candidateIndex]).host}"))
            exo.setMediaItem(mediaItem(candidates[candidateIndex]))
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 12_000)
            exo.prepare()
            exo.playWhenReady = true
        }
        fun nextCandidate(): Boolean {
            if (player !== exo || candidateIndex + 1 >= candidates.size) return false
            candidateIndex++
            onEvent(Event.Status("正在切换备用直播线路 ${candidateIndex + 1}/${candidates.size}"))
            beginCandidate()
            return true
        }
        timeoutAction = {
            if (player === exo && !nextCandidate()) onEvent(Event.Failure("直播缓冲超时，请检查网络后重试", true))
        }
        view.player = exo
        view.useController = false
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (player === exo) onEvent(Event.Diagnostic("视频解码器 $decoderName · 纹理输出"))
            }
        })
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exo) return
                handler.removeCallbacks(watchdog)
                when (state) {
                    Player.STATE_BUFFERING -> { onEvent(Event.Status("正在缓冲直播 · ${source.sourceName}")); handler.postDelayed(watchdog, 12_000) }
                    Player.STATE_READY -> {
                        onEvent(Event.Status(if (firstFrame) "直播源播放中" else "等待直播画面"))
                        if (!firstFrame) handler.postDelayed(watchdog, 12_000)
                    }
                    Player.STATE_ENDED -> onEvent(Event.Failure("直播连接已结束，请重新获取播放信息", true))
                }
            }
            override fun onRenderedFirstFrame() {
                if (player !== exo) return
                firstFrame = true
                handler.removeCallbacks(watchdog)
                onEvent(Event.FirstFrame)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                handler.removeCallbacks(watchdog)
                val network = error.errorCode in 2000..2999
                val format = error.errorCode in 3000..4999
                val httpCode = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                onEvent(Event.Diagnostic("播放器错误 ${error.errorCodeName} · HTTP ${httpCode ?: "-"}"))
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    if (windowRecoveries++ < 2) {
                        onEvent(Event.Status("正在恢复直播进度"))
                        exo.seekToDefaultPosition()
                        handler.postDelayed(watchdog, 12_000)
                        exo.prepare()
                        exo.playWhenReady = true
                        return
                    }
                    if (nextCandidate()) return
                    onEvent(Event.Failure("直播源进度中断，请重新连接", true))
                    return
                }
                if ((network || format) && nextCandidate()) return
                onEvent(Event.Failure(if (network) "直播连接失败或地址已失效" else "当前直播格式无法由本机原生解码", network || format))
            }
        })
        beginCandidate()
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        timeoutAction = null
        view.player = null
        val old = player
        player = null
        old?.release()
    }
}
