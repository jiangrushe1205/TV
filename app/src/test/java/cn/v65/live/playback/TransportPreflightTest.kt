package cn.v65.live.playback

import org.junit.Assert.*
import org.junit.Test

class TransportPreflightTest {
    private fun sample(prefix: List<Int>, scrambling: Int = 0): ByteArray {
        fun packet(pid: Int, data: List<Int>, control: Int = 0): ByteArray = ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47; this[1] = (0x40 or (pid shr 8)).toByte(); this[2] = pid.toByte(); this[3] = (0x10 or control).toByte()
            data.forEachIndexed { i, v -> this[4 + i] = v.toByte() }
        }
        val pat = listOf(0, 0, 0xb0, 13, 0, 1, 0xc1, 0, 0, 0, 1, 0xe1, 0, 0, 0, 0, 0)
        val pmt = listOf(0, 2, 0xb0, 18, 0, 1, 0xc1, 0, 0, 0xe1, 1, 0xf0, 0, 0x1b, 0xe1, 1, 0xf0, 0, 0, 0, 0, 0)
        return packet(0, pat) + packet(256, pmt) + packet(257, prefix, scrambling) + packet(257, prefix)
    }
    @Test fun acceptsStandardVideoPes() { assertNull(TransportPreflight.rejection(sample(listOf(0, 0, 1, 0xe0)))) }
    @Test fun rejectsNonstandardAndScrambledPayloads() {
        assertNotNull(TransportPreflight.rejection(sample(listOf(0x2b, 0x6a, 0xb9, 0))))
        assertNotNull(TransportPreflight.rejection(sample(listOf(0, 0, 1, 0xe0), 0x80)))
    }
    @Test fun rejectsMissingOrTruncatedTransport() {
        assertNotNull(TransportPreflight.rejection(ByteArray(5)))
        assertNotNull(TransportPreflight.rejection(ByteArray(800)))
    }
    @Test fun resolvesOnlyClearTransportSegments() {
        assertEquals("https://example.com/live/a.ts", ManifestPolicy.firstSegment("https://example.com/live/live.m3u8", "#EXTM3U\n#EXTINF:6,\na.ts"))
        assertNull(ManifestPolicy.firstSegment("https://example.com/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\na.ts"))
        assertNull(ManifestPolicy.firstSegment("https://example.com/a.m3u8", "#EXTM3U\nhttp://example.com/a.ts"))
    }
}
