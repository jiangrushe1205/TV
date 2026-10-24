package cn.v65.live.playback

import java.net.URI

/** A preflight only; parsing and decoding remain the responsibility of Media3. */
object ManifestPolicy {
    fun isCandidate(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host != null &&
            (uri.path.endsWith(".m3u8", true) || uri.path.endsWith(".mpd", true))
    }.getOrDefault(false)

    fun rejection(manifest: String, dash: Boolean): String? {
        val text = manifest.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (dash) {
            if (!text.contains("<MPD") || !Regex("\\btype\\s*=\\s*['\"]dynamic['\"]").containsMatchIn(text)) return "未取得 DASH 直播清单"
            if (text.contains("ContentProtection", true)) return "此节目需要官方播放器处理内容保护"
        } else {
            if (!text.startsWith("#EXTM3U")) return "媒体地址未返回标准 HLS 清单"
            if (text.contains("#EXT-X-ENDLIST")) return "检测到点播清单，未作为电视直播接入"
            val keys = text.lineSequence().filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }
            if (keys.any { line ->
                    Regex("METHOD=(?!NONE(?:,|$)|AES-128(?:,|$))").containsMatchIn(line) ||
                        (line.contains("KEYFORMAT=") && !line.contains("KEYFORMAT=\"identity\""))
                }) return "此节目需要官方播放器处理内容保护"
        }
        return null
    }

    fun firstVariant(baseUrl: String, manifest: String): String? {
        if (!manifest.contains("#EXT-X-STREAM-INF:")) return null
        val lines = manifest.lines().map { it.trim() }
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        val path = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
        return runCatching { URI(baseUrl).resolve(path).toString() }.getOrNull()?.takeIf {
            runCatching { URI(it).scheme.equals("https", true) }.getOrDefault(false)
        }
    }

    fun firstSegment(baseUrl: String, manifest: String): String? {
        if (manifest.contains("#EXT-X-STREAM-INF:") || manifest.contains("#EXT-X-MAP:") ||
            Regex("METHOD=(?!NONE(?:,|$))").containsMatchIn(manifest)) return null
        val path = manifest.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
        return runCatching { URI(baseUrl).resolve(path) }.getOrNull()
            ?.takeIf { it.scheme.equals("https", true) && it.path.endsWith(".ts", true) }?.toString()
    }

    fun expiration(url: String): Long? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || pair[0].lowercase() !in setOf("expires", "expire", "expiretime")) null
            else pair[1].toLongOrNull()?.let {
                when { it in 1_000_000_000L..99_999_999_999L -> it * 1000; it >= 1_000_000_000_000L -> it; else -> null }
            }
        }
    }
}
