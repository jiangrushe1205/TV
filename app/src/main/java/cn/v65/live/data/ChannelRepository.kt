package cn.v65.live.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChannelRepository(private val context: Context, private val client: SourceClient) {
    private val directory = File(context.filesDir, "source-cache").apply { mkdirs() }
    private val cache = synchronized(cacheInstances) {
        val cacheDirectory = directory
        cacheInstances.getOrPut(cacheDirectory.absolutePath) {
            SourceDataCache(
                read = { key ->
                    val file = File(cacheDirectory, key)
                    val bytes = AtomicFile(file).openRead().use { it.readBytes() }
                    SourceDataCache.Entry(bytes, file.lastModified())
                },
                write = { key, entry ->
                    val file = File(cacheDirectory, key)
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(entry.bytes); atomic.finishWrite(output) }
                    catch (e: Exception) { atomic.failWrite(output); throw e }
                    file.setLastModified(entry.savedAt)
                    cacheDirectory.listFiles()?.filter {
                        it.name.startsWith("epg-") && it.lastModified() < entry.savedAt - 3 * 86_400_000L
                    }?.forEach { it.delete() }
                },
            )
        }
    }

    /** Return the last known catalog immediately; channels() revalidates it in the background. */
    suspend fun cachedChannels(): Catalog = withContext(Dispatchers.IO) {
        val file = File(directory, CATALOG_KEY)
        val saved = runCatching { SiteProto.channels(AtomicFile(file).openRead().use { it.readBytes() }) }.getOrNull()
        if (saved != null) Catalog(saved, file.lastModified(), "本机缓存") else bundledCatalog()
    }

    suspend fun channels(forceRefresh: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        try {
            val result = cache.get(CATALOG_KEY, SiteProto::channels, { CATALOG_TTL }, forceRefresh) {
                val feed = SiteProto.televisionFeed(client.getBytes("https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav"))
                client.getBytes("https://capi.yangshipin.cn/api/oms/pc/page/$feed")
            }
            Catalog(
                result.data,
                result.savedAt,
                if (result.origin == SourceDataCache.Origin.NETWORK) "官方实时数据" else "本机缓存",
                if (result.origin == SourceDataCache.Origin.STALE) "联网更新失败，正在显示过期缓存频道" else null,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            bundledCatalog().copy(warning = "联网更新失败，频道信息可能已经变化")
        }
    }

    suspend fun schedule(
        channel: Channel,
        date: String = BeijingTime.dateKey(),
        forceRefresh: Boolean = false,
    ): Schedule = withContext(Dispatchers.IO) {
        require(channel.id.matches(Regex("[0-9]{5,20}")))
        require(date.matches(Regex("[0-9]{8}")))
        val key = "epg-${channel.id}-$date.pb"
        try {
            val result = cache.get(key, { SiteProto.programs(it, date) },
                { if (it.isEmpty()) EMPTY_EPG_TTL else EPG_TTL }, forceRefresh) {
                client.getBytes("https://capi.yangshipin.cn/api/yspepg/program/${channel.id}/$date")
            }
            Schedule(
                result.data,
                date,
                result.origin != SourceDataCache.Origin.NETWORK,
                if (result.origin == SourceDataCache.Origin.STALE) "联网更新失败，正在显示过期缓存节目单" else null,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Schedule(emptyList(), date, false, "节目单暂不可用")
        }
    }

    private fun bundledCatalog() = Catalog(
        SiteProto.channels(context.assets.open("catalog-20261022.pb").use { it.readBytes() }),
        BeijingTime.parse("20261022", "21:00"),
        "内置频道快照（2026-10-22）",
    )

    companion object {
        private const val CATALOG_KEY = "catalog.pb"
        private const val CATALOG_TTL = 24 * 60 * 60_000L
        private const val EPG_TTL = 5 * 60_000L
        private const val EMPTY_EPG_TTL = 60_000L
        private val cacheInstances = mutableMapOf<String, SourceDataCache>()
    }
}
