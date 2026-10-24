package cn.v65.live.playback

import android.content.Context
import cn.v65.live.data.Channel
import cn.v65.live.data.SourceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Direct anonymous official API; no WebView, media decryption, account or server. */
class PlaybackResolver(context: Context, private val client: SourceClient) {
    private val publicSources = PublicSourceRepository(context, client)
    suspend fun refreshPublicSources() = publicSources.refresh()
    private val mappings = JSONArray(context.assets.open("native-channels-20261023.json").bufferedReader().use { it.readText() })
        .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("livePid") } }
    private val recentSources = mutableMapOf<String, PlaybackResolution.NativeSource>()
    private val upstreamHeaders = mapOf("User-Agent" to "qqlive", "Referer" to "https://live.cctv.cn/")

    suspend fun resolve(channel: Channel, forceRefresh: Boolean = false): PlaybackResolution {
        // Cached public playlists start immediately; no web verification or preflight wait.
        if (!forceRefresh) publicSources.source(channel)?.let { return it }
        val mapping = mappings[channel.id]
            ?: return PlaybackResolution.Unavailable("此频道尚无公开原生直播源，需要官方账号授权的频道暂不接入")
        val now = System.currentTimeMillis()
        recentSources.entries.removeAll { now - it.value.resolvedAt >= 5 * 60_000 || (it.value.expiresAt ?: Long.MAX_VALUE) - now < 15_000 }
        if (forceRefresh) recentSources.remove(channel.id)
        else recentSources[channel.id]?.let { return it }
        return try {
            withTimeoutOrNull(15_000) resolve@{
                val ticket = YspTicket.create(mapping.getString("channelId"))
                val query = linkedMapOf(
                    "atime" to "120", "livepid" to channel.id, "cnlid" to mapping.getString("channelId"),
                    "appVer" to YspTicket.APP_VERSION, "app_version" to "300090", "caplv" to "1", "cmd" to "2",
                    "defn" to mapping.optString("defn", "fhd"), "device" to "iPhone", "encryptVer" to "4.2",
                    "getpreviewinfo" to "0", "hevclv" to "0", "lang" to "zh-Hans_CN", "livequeue" to "0",
                    "logintype" to "1", "nettype" to "1", "newnettype" to "1", "newplatform" to YspTicket.PLATFORM.toString(),
                    "platform" to YspTicket.PLATFORM.toString(), "sdtfrom" to "v3021", "spacode" to "23", "spaudio" to "1",
                    "spdemuxer" to "6", "spdrm" to "2", "spdynamicrange" to "1", "spflv" to "1", "spflvaudio" to "1",
                    "sphdrfps" to "60", "sphttps" to "1", "spvcode" to "SCgzMDoxMDgwLDYwOjEwODB8MzA6MTA4MCw2MDoxMDgwKQ==",
                    "spvideo" to "4", "stream" to "1", "system" to "1", "sysver" to "ios18.2.1", "uhd_flag" to "0",
                    "cKey" to ticket.cKey, "guid" to ticket.guid, "fntick" to ticket.timestamp.toString(),
                    "flowid" to ticket.flowId, "playbacktime" to "0"
                )
                val url = "https://bkliveinfo.ysp.cctv.cn/".toHttpUrl().newBuilder().apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build().toString()
                val payload = JSONObject(client.getBytes(url, mapOf("User-Agent" to "qqlive"), 256 * 1024).toString(Charsets.UTF_8))
                if (payload.optInt("iretcode", -1) != 0) return@resolve PlaybackResolution.Unavailable(
                    "官方拒绝播放：${payload.optString("errinfo", "未返回公开直播源").take(140)}（${payload.optInt("iretcode", -1)}）")
                val urls = mutableListOf(payload.optString("playurl"))
                val backups = payload.opt("backurl_list") ?: payload.opt("backurlList") ?: payload.opt("backurl")
                if (backups is JSONArray) for (i in 0 until backups.length()) {
                    val item = backups.opt(i)
                    urls += if (item is JSONObject) item.optString("url", item.optString("playurl")) else item?.toString().orEmpty()
                } else if (backups is String) urls += backups.split(';', ',')
                val candidates = urls.distinct().filter(::isOfficialMediaUrl)
                if (candidates.isEmpty()) return@resolve PlaybackResolution.Unavailable("官方没有返回可用的 HLS 直播源")
                // Do not consume an upstream request before the player: some ticket/CDN pairs reject repeats.
                PlaybackResolution.NativeSource(candidates.first(), upstreamHeaders, "application/x-mpegURL",
                    expiresAt = ManifestPolicy.expiration(candidates.first()), alternateUrls = candidates.drop(1))
                    .also { recentSources[channel.id] = it }
            } ?: PlaybackResolution.Unavailable("官方直播源请求超时，请重试或切换频道")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PlaybackResolution.Unavailable("官方直播源获取失败，请检查网络后重试")
        }
    }

    // HTTP calls are owned by the caller's coroutine and cancelled by SourceClient.
    fun close() = Unit

    companion object {
        fun isOfficialMediaUrl(value: String): Boolean = runCatching {
            val url = value.toHttpUrl()
            url.isHttps && (url.host == "cctv.cn" || url.host.endsWith(".cctv.cn") || url.host == "cctv.com" || url.host.endsWith(".cctv.com"))
        }.getOrDefault(false)
    }
}
