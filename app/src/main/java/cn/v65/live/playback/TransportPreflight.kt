package cn.v65.live.playback

/** Checks MPEG-TS framing only; protected payloads are left to the official player. */
object TransportPreflight {
    fun rejection(bytes: ByteArray): String? {
        fun u(i: Int) = bytes[i].toInt() and 255
        val offset = (0 until minOf(188, bytes.size - 376)).firstOrNull {
            u(it) == 0x47 && u(it + 188) == 0x47 && u(it + 376) == 0x47
        } ?: return "无法验证标准直播视频数据，请使用官方网页"
        val packets = (offset..bytes.size - 188 step 188).filter { u(it) == 0x47 }
        fun pid(p: Int) = ((u(p + 1) and 31) shl 8) or u(p + 2)
        fun payload(p: Int): Int? {
            val control = (u(p + 3) shr 4) and 3
            if (control == 0 || control == 2 || u(p + 1) and 0x40 == 0) return null
            val start = p + 4 + if (control == 3) 1 + u(p + 4) else 0
            return start.takeIf { it + 3 < p + 188 }
        }
        fun section(p: Int, type: Int): Pair<Int, Int>? {
            val start = payload(p) ?: return null
            val s = start + 1 + u(start)
            if (s + 12 >= p + 188 || u(s) != type || u(s + 1) and 0x80 == 0) return null
            val end = s + 3 + (((u(s + 1) and 15) shl 8) or u(s + 2))
            return if (end <= p + 188 && end >= s + 12) s to end - 4 else null
        }
        val pmtPids = mutableSetOf<Int>()
        for (p in packets.filter { pid(it) == 0 }) {
            val (start, end) = section(p, 0) ?: continue
            for (i in start + 8..end - 4 step 4) {
                if (u(i) != 0 || u(i + 1) != 0) pmtPids += ((u(i + 2) and 31) shl 8) or u(i + 3)
            }
        }
        val videoPids = mutableSetOf<Int>()
        for (p in packets.filter { pid(it) in pmtPids }) {
            val (start, end) = section(p, 2) ?: continue
            var i = start + 12 + (((u(start + 10) and 15) shl 8) or u(start + 11))
            while (i + 5 <= end) {
                if (u(i) in setOf(0x02, 0x10, 0x1b, 0x24)) videoPids += ((u(i + 1) and 31) shl 8) or u(i + 2)
                i += 5 + (((u(i + 3) and 15) shl 8) or u(i + 4))
            }
        }
        var starts = 0
        for (p in packets.filter { pid(it) in videoPids }) {
            if (u(p + 3) and 0xc0 != 0) return "此直播的视频传输需要官方播放器处理"
            val start = payload(p) ?: continue
            starts++
            if (u(start) != 0 || u(start + 1) != 0 || u(start + 2) != 1) {
                return "此直播的视频包不是标准 MPEG-TS，原生播放可能灰屏，请使用官方网页"
            }
        }
        return if (videoPids.isEmpty() || starts == 0) "尚未验证标准视频轨，请使用官方网页" else null
    }
}
