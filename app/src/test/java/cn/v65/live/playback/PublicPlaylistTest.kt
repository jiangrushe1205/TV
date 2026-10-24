package cn.v65.live.playback

import org.junit.Assert.*
import org.junit.Test

class PublicPlaylistTest {
    @Test fun distinguishesNumberedAndPlusChannels() {
        assertEquals("CCTV1", PublicPlaylist.channelKey("CCTV-1 综合 (720p)"))
        assertEquals("CCTV13", PublicPlaylist.channelKey("CCTV13新闻"))
        assertEquals("CCTV5+", PublicPlaylist.channelKey("CCTV-5+ 体育赛事"))
        assertEquals("CCTV4K", PublicPlaylist.channelKey("CCTV-4K (1080p)"))
        assertNotEquals(PublicPlaylist.channelKey("CCTV1"), PublicPlaylist.channelKey("CCTV13"))
    }

    @Test fun parsesMetadataCommentsAndDuplicatesWithoutExecutingOptions() {
        val rows = PublicPlaylist.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV13" group-title="General",CCTV-13 (720p)
            #EXTVLCOPT:http-user-agent=ignored
            https://example.com/live.m3u8?x=1
            #EXTINF:-1,CCTV-13
            https://example.com/live.m3u8?x=1
            #EXTINF:-1,东方卫视
            http://example.com:8080/live.m3u8
        """.trimIndent())
        assertEquals(2, rows.size)
        assertEquals("CCTV13", rows[0].channel)
        assertEquals("东方卫视", rows[1].channel)
    }

    @Test fun rejectsNonMediaAndNonHttpLinks() {
        val rows = PublicPlaylist.parse("#EXTM3U\n#EXTINF:-1,CCTV1\nfile:///sdcard/live.m3u8\n#EXTINF:-1,CCTV2\nhttps://example.com/index.html")
        assertTrue(rows.isEmpty())
        assertFalse(PublicPlaylist.validUrl("https://user:secret@example.com/a.m3u8"))
        assertThrows(IllegalArgumentException::class.java) { PublicPlaylist.parse("<html>Bad Gateway</html>") }
    }

    @Test fun filtersExpiredSignedUrlsAndKeepsUnsignedEndpoints() {
        val now = 1_700_000_000_000L
        assertTrue(PublicPlaylist.isExpired("https://example.com/a.m3u8?auth_key=1699999999-1-token-hash", now))
        assertTrue(PublicPlaylist.isExpired("https://example.com/a.m3u8?expires=1699999999", now))
        assertFalse(PublicPlaylist.isExpired("https://example.com/a.m3u8?auth_key=1700001000-1-token-hash", now))
        assertFalse(PublicPlaylist.isExpired("http://example.com:81/live.m3u8", now))
    }
}
