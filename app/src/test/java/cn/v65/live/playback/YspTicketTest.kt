package cn.v65.live.playback

import org.junit.Assert.*
import org.junit.Test

class YspTicketTest {
    // Cross-language vector generated from upstream ckey.js, not the Kotlin implementation.
    @Test fun matchesUpstreamAnonymousTicketVector() {
        val ticket = YspTicket.create("2029797201", 1700000000000L,
            "00112233445566778899aabbccddeeff", "A1B2C3D4") { n -> ByteArray(n) { 0x5a } }
        assertEquals("--01LqRkXvg6gA2OqRLSsJxXK1hGM4aV-Sosog6ism4CxOq9SPOfzcqPWa8gvlYqwBGcVo-NPBcSiq0kVPfxf_kbAIwHd-KoeDADQYdcSQYMLsGWSWINfGnG_yiETU4hoiTUGw05ceXTxyBC37d1XHey1OxwcwlgSDFsdB9l8BJlsi4g3eC_kubp6obFAwupqOX1_TnHSV0bfiy668Lyd8acwJob8UbMstCLEIWLoRel47ueOFBZyX1lBh7ELbujgxL_a32TakkEzfxc4gJU5qKJUMm5DkYTf3wmSU9KtCJgNpbAKuvS5ZB_yVKk9GD8i4y95c1FOdjNRUEyKKUQe9_1_ZBtoG3jx4UxrIyJL6LfZCkh_is6NKkCzHsS5Lgw8Xywc8E46noE5XIdEbUDQpc38h_T9JQ6SzyWx9t8PTJUVPbKvX0V7_g567avmSJyCp4Yax_60OD1QYydGNGiV-oOzZm0zw0", ticket.cKey)
        assertEquals(1700000000L, ticket.timestamp)
    }
    @Test fun confinesMediaToOfficialHttpsHosts() {
        assertTrue(PlaybackResolver.isOfficialMediaUrl("https://live-dtocnc-cdn.ysp.cctv.cn/a.m3u8"))
        listOf("http://live.cctv.cn/a", "https://cctv.cn.example.com/a", "https://cctv.cn@evil.example/a", "file:///a").forEach {
            assertFalse(PlaybackResolver.isOfficialMediaUrl(it))
        }
    }
}
