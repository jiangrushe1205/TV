package cn.v65.live.playback

sealed interface PlaybackResolution {
    data class NativeSource(
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String,
        val resolvedAt: Long = System.currentTimeMillis(),
        val expiresAt: Long? = null,
        val alternateUrls: List<String> = emptyList(),
        val sourceName: String = "央视频",
    ) : PlaybackResolution
    data class OfficialPage(val url: String, val reason: String) : PlaybackResolution
    data class Unavailable(val reason: String) : PlaybackResolution
}

data class CapturedMedia(val url: String, val headers: Map<String, String>)
