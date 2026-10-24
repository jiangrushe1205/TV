package cn.v65.live.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class Channel(
    val id: String,
    val name: String,
    val code: String = "",
    val streamId: String = "",
    val group: String = "",
    val isVip: Boolean = false,
    val isLimitedFree: Boolean = false,
    val is4k: Boolean = false,
) {
    val officialUrl: String get() = "https://www.yangshipin.cn/tv/home?pid=$id"
    val badge: String get() = when {
        isLimitedFree -> "限免"
        isVip -> "VIP"
        is4k -> "4K"
        else -> ""
    }
}

data class Program(
    val id: String,
    val name: String,
    val startsAt: Long,
    val endsAt: Long,
    val copyrightFlag: String,
    val isVip: Boolean,
) {
    fun isCurrent(now: Long) = startsAt <= now && now < endsAt
}

data class Catalog(val channels: List<Channel>, val updatedAt: Long, val origin: String, val warning: String? = null)
data class Schedule(val programs: List<Program>, val date: String, val fromCache: Boolean, val warning: String? = null)

object BeijingTime {
    val zone: TimeZone get() = TimeZone.getTimeZone("Asia/Shanghai")
    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone; isLenient = false }
    fun dateKey(now: Long = System.currentTimeMillis()) = format("yyyyMMdd").format(now)
    fun clock(now: Long = System.currentTimeMillis()) = format("HH:mm").format(now)
    fun dateLabel(now: Long = System.currentTimeMillis()) = format("MM月dd日 EEEE").format(now)
    fun timestamp(now: Long = System.currentTimeMillis()) = format("yyyy-MM-dd HH:mm:ss").format(now)
    fun parse(date: String, time: String): Long = runCatching { format("yyyyMMdd HH:mm").parse("$date $time")!!.time }.getOrDefault(0L)
    fun nextDay(date: String): Long = Calendar.getInstance(zone).apply {
        timeInMillis = parse(date, "00:00")
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
    fun normalizeEpoch(value: Long): Long = when {
        value in 1_000_000_000L..99_999_999_999L -> value * 1000
        value >= 1_000_000_000_000L -> value
        else -> 0L
    }
}
