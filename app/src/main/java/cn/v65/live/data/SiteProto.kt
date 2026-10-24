package cn.v65.live.data

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.io.IOException

/** Field numbers are from the schema shipped by the official PC application. */
object SiteProto {
    private data class Fields(val bytes: Map<Int, List<ByteArray>>, val numbers: Map<Int, List<Long>>) {
        fun messages(field: Int) = bytes[field].orEmpty().map(::read)
        fun text(field: Int) = bytes[field]?.firstOrNull()?.toString(Charsets.UTF_8).orEmpty()
        fun number(field: Int) = numbers[field]?.firstOrNull() ?: 0L
        fun flag(field: Int) = number(field) != 0L
    }

    private fun read(bytes: ByteArray): Fields {
        if (bytes.size > 2 * 1024 * 1024) throw IOException("数据响应过大")
        val input = CodedInputStream.newInstance(bytes)
        val strings = mutableMapOf<Int, MutableList<ByteArray>>()
        val numbers = mutableMapOf<Int, MutableList<Long>>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            val field = WireFormat.getTagFieldNumber(tag)
            when (WireFormat.getTagWireType(tag)) {
                WireFormat.WIRETYPE_LENGTH_DELIMITED -> strings.getOrPut(field) { mutableListOf() }.add(input.readByteArray())
                WireFormat.WIRETYPE_VARINT -> numbers.getOrPut(field) { mutableListOf() }.add(input.readInt64())
                else -> if (!input.skipField(tag)) throw IOException("不支持的数据格式")
            }
        }
        return Fields(strings, numbers)
    }

    private fun response(bytes: ByteArray): Fields = read(bytes).also {
        if (it.number(1) != 200L) throw IOException("官方接口返回 ${it.number(1)}，请稍后刷新")
    }

    fun televisionFeed(bytes: ByteArray): String = response(bytes).messages(2).flatMap { it.messages(2) }
        .firstOrNull { it.number(10) == 2L }?.text(4)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?: throw IOException("官方导航中暂未发现电视栏目")

    fun channels(bytes: ByteArray): List<Channel> = response(bytes).messages(2)
        .flatMap { it.messages(2) }.flatMap { it.messages(15) }
        .mapNotNull { item ->
            val id = item.text(4)
            val name = item.text(2)
            if (!id.matches(Regex("[0-9]{5,20}")) || name.isBlank()) null else Channel(
                id, name, item.text(3), item.text(6), item.text(11), item.flag(7), item.flag(14), item.flag(8)
            )
        }.distinctBy { it.id }.also { if (it.isEmpty()) throw IOException("官方频道列表为空或格式已更新") }

    fun programs(bytes: ByteArray, date: String): List<Program> {
        val programs = response(bytes).messages(2).mapNotNull { item ->
            val start = BeijingTime.normalizeEpoch(item.number(3)).takeIf { it > 0 } ?: BeijingTime.parse(date, item.text(5))
            val end = BeijingTime.normalizeEpoch(item.number(4)).takeIf { it > 0 } ?: BeijingTime.parse(date, item.text(6))
            if (start == 0L || item.text(2).isBlank()) null else Program(
                item.text(1), item.text(2), start, end, item.text(9), item.flag(8)
            )
        }.distinctBy { it.id to it.startsAt }.sortedBy { it.startsAt }
        return programs.mapIndexed { index, program ->
            if (program.endsAt > program.startsAt) program else program.copy(
                endsAt = programs.getOrNull(index + 1)?.startsAt ?: BeijingTime.nextDay(date)
            )
        }
    }
}
