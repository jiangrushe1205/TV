package cn.v65.live.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChannelRepository(private val context: Context, private val client: SourceClient) {
    private val cache = File(context.filesDir, "source-cache").apply { mkdirs() }
    private val catalogFile = File(cache, "catalog.pb")

    suspend fun cachedChannels(): Catalog = withContext(Dispatchers.IO) {
        val saved = runCatching { SiteProto.channels(catalogFile.readBytes()) }.getOrNull()
        if (saved != null) Catalog(saved, catalogFile.lastModified(), "本机缓存", "正在后台更新官方频道")
        else Catalog(
            SiteProto.channels(context.assets.open("catalog-20261022.pb").use { it.readBytes() }),
            BeijingTime.parse("20261022", "21:00"), "内置频道快照（2026-10-22）", "正在后台更新官方频道"
        )
    }

    suspend fun channels(): Catalog = withContext(Dispatchers.IO) {
        try {
            val feed = SiteProto.televisionFeed(client.getBytes("https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav"))
            val bytes = client.getBytes("https://capi.yangshipin.cn/api/oms/pc/page/$feed")
            val channels = SiteProto.channels(bytes)
            save(catalogFile, bytes)
            Catalog(channels, System.currentTimeMillis(), "官方实时数据")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val saved = runCatching { SiteProto.channels(catalogFile.readBytes()) }.getOrNull()
            if (saved != null) Catalog(saved, catalogFile.lastModified(), "本机缓存", "联网更新失败，正在显示缓存频道")
            else Catalog(
                SiteProto.channels(context.assets.open("catalog-20261022.pb").use { it.readBytes() }),
                BeijingTime.parse("20261022", "21:00"), "内置频道快照（2026-10-22）", "联网更新失败，频道信息可能已经变化"
            )
        }
    }

    suspend fun schedule(channel: Channel, date: String = BeijingTime.dateKey()): Schedule = withContext(Dispatchers.IO) {
        require(channel.id.matches(Regex("[0-9]{5,20}")))
        require(date.matches(Regex("[0-9]{8}")))
        val file = File(cache, "epg-${channel.id}-$date.pb")
        try {
            val bytes = client.getBytes("https://capi.yangshipin.cn/api/yspepg/program/${channel.id}/$date")
            val programs = SiteProto.programs(bytes, date)
            save(file, bytes)
            Schedule(programs, date, false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val programs = runCatching { SiteProto.programs(file.readBytes(), date) }.getOrDefault(emptyList())
            Schedule(programs, date, true, if (programs.isEmpty()) "节目单暂不可用" else "正在显示缓存节目单")
        }
    }

    private fun save(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
        cache.listFiles()?.filter { it.name.startsWith("epg-") && it.lastModified() < System.currentTimeMillis() - 3 * 86_400_000L }
            ?.forEach { it.delete() }
    }
}
