package cn.v65.live.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Validate before use or replacement; failed refreshes preserve the last good entry. */
internal class SourceDataCache(
    private val read: (String) -> Entry?,
    private val write: (String, Entry) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val bytes: ByteArray, val savedAt: Long)
    enum class Origin { CACHE, NETWORK, STALE }
    data class Value<T>(val data: T, val savedAt: Long, val origin: Origin)
    private val mutex = Mutex()

    suspend fun <T> get(
        key: String,
        decode: (ByteArray) -> T,
        maxAge: (T) -> Long,
        forceRefresh: Boolean = false,
        fetch: suspend () -> ByteArray,
    ): Value<T> = mutex.withLock {
        val saved = runCatching {
            read(key)?.let { Value(decode(it.bytes), it.savedAt, Origin.CACHE) }
        }.getOrNull()
        val age = saved?.let { now() - it.savedAt }
        if (!forceRefresh && saved != null && saved.savedAt > 0 && age != null && age >= 0 && age < maxAge(saved.data)) {
            return@withLock saved
        }
        try {
            val bytes = fetch()
            val data = decode(bytes)
            val savedAt = now()
            // Disk failure must not prevent playback with valid data from this session.
            runCatching { write(key, Entry(bytes, savedAt)) }
            Value(data, savedAt, Origin.NETWORK)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            saved?.copy(origin = Origin.STALE) ?: throw e
        }
    }
}
