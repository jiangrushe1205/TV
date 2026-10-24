package cn.v65.live.playback

import org.junit.Assert.*
import org.junit.Test

class ManifestPolicyTest {
    @Test fun candidatesRequireHttpsAndMediaExtensions() {
        assertTrue(ManifestPolicy.isCandidate("https://example.com/live/index.m3u8?token=x"))
        assertTrue(ManifestPolicy.isCandidate("https://example.com/a.mpd"))
        listOf("http://example.com/a.m3u8", "blob:https://example.com/a", "file:///tmp/a.m3u8", "https://example.com/?a=.m3u8").forEach { assertFalse(ManifestPolicy.isCandidate(it)) }
    }
    @Test fun acceptsStandardLiveHlsAndAes128() {
        assertNull(ManifestPolicy.rejection("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts", false))
        assertNull(ManifestPolicy.rejection("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:6,\na.ts", false))
    }
    @Test fun rejectsProtectedOrNonLiveResponses() {
        assertNotNull(ManifestPolicy.rejection("<html>Access denied</html>", false))
        assertNotNull(ManifestPolicy.rejection("#EXTM3U\n#EXT-X-ENDLIST", false))
        assertNotNull(ManifestPolicy.rejection("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://test\"", false))
        assertNotNull(ManifestPolicy.rejection("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"vendor\"", false))
        assertNotNull(ManifestPolicy.rejection("<MPD type=\"dynamic\"><ContentProtection/></MPD>", true))
        assertNotNull(ManifestPolicy.rejection("<MPD type=\"static\"/>", true))
        assertNull(ManifestPolicy.rejection("<MPD type=\"dynamic\"/>", true))
    }
    @Test fun resolvesRelativeVariantWithoutForwardingHttp() {
        assertEquals("https://example.com/live/low/index.m3u8", ManifestPolicy.firstVariant("https://example.com/live/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nlow/index.m3u8"))
        assertNull(ManifestPolicy.firstVariant("https://example.com/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttp://example.com/a.m3u8"))
    }
    @Test fun expirationUsesSecondsOrMilliseconds() {
        assertEquals(1_800_000_000_000L, ManifestPolicy.expiration("https://example.com/a.m3u8?expires=1800000000"))
        assertEquals(1_800_000_000_000L, ManifestPolicy.expiration("https://example.com/a.m3u8?expireTime=1800000000000"))
        assertNull(ManifestPolicy.expiration("https://example.com/a.m3u8?token=abc"))
    }
}
