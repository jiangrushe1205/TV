package cn.v65.live.data

import org.junit.Assert.*
import org.junit.Test
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone

class SiteProtoTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/official/$name.pb")!!.use { it.readBytes() }

    @Test fun discoversTelevisionFeedFromOfficialNavigation() {
        assertEquals("PG00000004", SiteProto.televisionFeed(fixture("navigation")))
    }
    @Test fun parsesOfficialChannelSnapshotAndKeepsAccessLabels() {
        val channels = SiteProto.channels(fixture("page"))
        assertTrue(channels.size >= 30)
        assertEquals(channels.size, channels.distinctBy { it.id }.size)
        listOf("CCTV1", "CCTV13", "CCTV5").forEach { name ->
            val channel = channels.first { it.name == name }
            assertTrue(channel.id.matches(Regex("[0-9]+")))
            assertTrue(channel.officialUrl.endsWith("pid=${channel.id}"))
        }
        assertTrue(channels.any { it.isVip })
        assertTrue(channels.any { it.is4k })
    }
    @Test fun parsesOfficialScheduleAndFindsCurrentProgram() {
        val programs = SiteProto.programs(fixture("epg"), "20261022")
        assertTrue(programs.size > 20)
        assertTrue(programs.all { it.endsAt > it.startsAt })
        assertEquals(programs.sortedBy { it.startsAt }, programs)
        val noon = programs.first { it.name == "新闻30分" }
        assertEquals("12:00", BeijingTime.clock(noon.startsAt))
        assertTrue(noon.isCurrent(noon.startsAt))
        assertFalse(noon.isCurrent(noon.endsAt))
    }
    @Test fun unknownFieldsDoNotBreakCatalog() {
        val output = ByteArrayOutputStream()
        output.write(fixture("page"))
        CodedOutputStream.newInstance(output).apply { writeString(199, "future field"); flush() }
        assertEquals(SiteProto.channels(fixture("page")), SiteProto.channels(output.toByteArray()))
    }
    @Test fun rejectsMalformedOrFailedResponses() {
        assertThrows(Exception::class.java) { SiteProto.channels(byteArrayOf(0x08, 0x01)) }
        assertThrows(Exception::class.java) { SiteProto.channels(byteArrayOf(0x12, 0x7f, 0x01)) }
    }
    @Test fun beijingDatesIgnoreHostTimezoneAndHandleMidnight() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val midnight = BeijingTime.parse("20261022", "00:00")
            assertEquals("20261022", BeijingTime.dateKey(midnight))
            assertEquals("00:00", BeijingTime.clock(midnight))
            assertEquals("20261023", BeijingTime.dateKey(BeijingTime.nextDay("20261022")))
            assertEquals(midnight, BeijingTime.normalizeEpoch(midnight / 1000))
        } finally { TimeZone.setDefault(old) }
    }
}
