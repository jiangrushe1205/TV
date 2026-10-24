package cn.v65.live.playback

import java.net.URI
import java.util.Locale

/** Reads only channel metadata and HTTP(S) endpoints, never playlist-provided commands. */
object PublicPlaylist {
    data class Entry(val channel: String, val url: String)

    fun channelKey(name: String): String {
        val cleaned = name.uppercase(Locale.ROOT).replace("央视", "CCTV").replace("中央", "CCTV")
            .replace("＋", "+").replace("-", "").replace(" ", "")
        Regex("^CCTV(\\d{1,2})(\\+|K)?(?!\\d)").find(cleaned)?.let {
            return "CCTV${it.groupValues[1]}${it.groupValues[2]}"
        }
        return cleaned.substringBefore('(').substringBefore('（').replace("高清", "").replace("HD", "")
    }

    fun parse(text: String): List<Entry> {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "Invalid channel list" }
        val result = mutableListOf<Entry>()
        var name: String? = null
        for (raw in text.lineSequence().take(10_000)) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF:") -> name = Regex("tvg-name=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    ?: line.substringAfterLast(',').takeIf { it.isNotBlank() }
                line.isEmpty() || line.startsWith('#') -> Unit
                else -> {
                    val channel = name
                    name = null
                    if (channel != null && validUrl(line)) result += Entry(channelKey(channel), line)
                }
            }
        }
        return result.distinct()
    }

    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
            uri.fragment == null && uri.path.endsWith(".m3u8", true)
    }.getOrDefault(false)

    fun isExpired(url: String, now: Long): Boolean {
        val signedExpiry = runCatching { URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("auth_key=") }
            ?.substringAfter('=')?.substringBefore('-')?.toLongOrNull()?.times(1000) }.getOrNull()
        return (signedExpiry ?: ManifestPolicy.expiration(url) ?: Long.MAX_VALUE) < now + 15_000
    }
}
