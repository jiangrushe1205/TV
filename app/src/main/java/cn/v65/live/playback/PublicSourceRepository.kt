package cn.v65.live.playback

import android.content.Context
import cn.v65.live.data.Channel
import cn.v65.live.data.SourceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI

/** User-supplied public CCTV/province lists. No pay-channel list or service is used. */
class PublicSourceRepository(private val context: Context, private val client: SourceClient) {
    private val files = listOf("cn_cctv.m3u8", "cn_province.m3u8")
    private val entries = mutableMapOf<String, List<PublicPlaylist.Entry>>()
    private val preferredHosts = context.assets.open("public-source-priority.txt").bufferedReader().useLines { lines ->
        lines.map { it.trim().removePrefix("\uFEFF") }.toList()
    }

    init {
        for (name in files) entries[name] = readSaved(name)
    }

    private fun cache(name: String) = File(context.filesDir, "public-$name")
    private fun readSaved(name: String): List<PublicPlaylist.Entry> = runCatching {
        val saved = cache(name)
        PublicPlaylist.parse(if (saved.exists()) saved.readText() else context.assets.open("public-$name").bufferedReader().use { it.readText() })
    }.getOrElse { PublicPlaylist.parse(context.assets.open("public-$name").bufferedReader().use { it.readText() }) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (name in files) {
            if (System.currentTimeMillis() - cache(name).lastModified() < 6 * 60 * 60_000L) continue
            try {
                val bytes = withTimeoutOrNull(8_000) {
                    client.getBytes("https://raw.githubusercontent.com/best-fan/iptv-sources/main/$name", maxBytes = 1024 * 1024)
                } ?: continue
                val parsed = PublicPlaylist.parse(bytes.toString(Charsets.UTF_8))
                if (parsed.isEmpty()) continue
                val pending = File(context.filesDir, "public-$name.tmp")
                pending.writeBytes(bytes)
                if (!pending.renameTo(cache(name))) { pending.delete(); continue }
                synchronized(entries) { entries[name] = parsed }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Keep the last valid snapshot on HTTP failure or an invalid upstream list.
            }
        }
    }

    fun source(channel: Channel): PlaybackResolution.NativeSource? {
        if (channel.isVip || channel.isLimitedFree) return null
        val key = PublicPlaylist.channelKey(channel.name)
        val now = System.currentTimeMillis()
        val urls = synchronized(entries) { entries.values.flatten() }
            .filter { it.channel == key && !PublicPlaylist.isExpired(it.url, now) }
            // Device capture 2026-10-23: this upstream row says CCTV5 but carries CCTV5+.
            .filterNot { key == "CCTV5" && URI(it.url).host == "153.0.171.163" && URI(it.url).path == "/tsfile/live/1015_1.m3u8" }
            .map { it.url }.distinct().sortedBy { url ->
                val host = URI(url).host
                val rank = preferredHosts.indexOf(host)
                when {
                    host == "event.pull.hebtv.com" -> 0
                    rank >= 0 -> rank + 1
                    url.startsWith("https://") -> 100
                    else -> 200
                }
            }.take(4)
        if (urls.isEmpty()) return null
        return PlaybackResolution.NativeSource(urls.first(), mapOf("User-Agent" to "V65Live/0.4.0"),
            "application/x-mpegURL", alternateUrls = urls.drop(1), sourceName = "公开源")
    }
}
