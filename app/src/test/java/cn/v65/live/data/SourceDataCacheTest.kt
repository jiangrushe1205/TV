package cn.v65.live.data

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDataCacheTest {
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun text(value: ByteArray) = value.toString(Charsets.UTF_8)

    @Test fun freshEntryIsServedWithoutNetwork() = runBlocking {
        var saved = SourceDataCache.Entry(bytes("cached"), 900)
        val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 1_000 })
        val result = cache.get("key", ::text, { 500 }, fetch = { error("fresh cache should avoid request") })
        assertEquals("cached", result.data)
        assertEquals(SourceDataCache.Origin.CACHE, result.origin)
    }

    @Test fun expiredEntryRefreshesAndReplacesOnlyAfterValidation() = runBlocking {
        var saved = SourceDataCache.Entry(bytes("old"), 100)
        val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 1_000 })
        val result = cache.get("key", ::text, { 900 }, fetch = { bytes("valid") })
        assertEquals("valid", result.data)
        assertEquals("valid", text(saved.bytes))
        assertEquals(SourceDataCache.Origin.NETWORK, result.origin)
    }

    @Test fun missingOrCorruptEntryIsFetchedAndCached() = runBlocking {
        for (original in listOf(null, SourceDataCache.Entry(bytes("corrupt"), 900))) {
            var saved = original
            val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 1_000 })
            val result = cache.get("key", { text(it).also { value -> require(value == "valid") } }, { 500 }, fetch = { bytes("valid") })
            assertEquals("valid", result.data)
            assertEquals("valid", text(saved!!.bytes))
        }
    }

    @Test fun explicitRefreshBypassesFreshCache() = runBlocking {
        var saved = SourceDataCache.Entry(bytes("old"), 900)
        val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 1_000 })
        val result = cache.get("key", ::text, { 500 }, forceRefresh = true, fetch = { bytes("new") })
        assertEquals("new", result.data)
        assertEquals("new", text(saved.bytes))
    }

    @Test fun failedNetworkReturnsExpiredCacheWithoutExtendingExpiry() = runBlocking {
        var saved = SourceDataCache.Entry(bytes("old"), 1)
        val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 1_000 })
        val result = cache.get("key", ::text, { 500 }, fetch = { throw java.io.IOException("offline") })
        assertEquals(SourceDataCache.Origin.STALE, result.origin)
        assertEquals(1L, saved.savedAt)
    }

    @Test fun cancelledRefreshPropagatesInsteadOfReturningCache() = runBlocking {
        val cache = SourceDataCache({ SourceDataCache.Entry(bytes("old"), 1) }, { _, _ -> error("no write") }, { 1_000 })
        val error = runCatching {
            cache.get("key", ::text, { 500 }, fetch = { throw CancellationException("obsolete request") })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun clockRollbackDoesNotKeepEntryFreshIndefinitely() = runBlocking {
        val cache = SourceDataCache({ SourceDataCache.Entry(bytes("future"), 2_000) }, { _, _ -> }, { 1_000 })
        assertEquals("new", cache.get("key", ::text, { 500 }, fetch = { bytes("new") }).data)
    }

    @Test fun invalidRefreshKeepsLastGoodEntryAndReturnsItAsStale() = runBlocking {
        val saved = SourceDataCache.Entry(bytes("last-good"), 100)
        var writes = 0
        val cache = SourceDataCache({ saved }, { _, _ -> writes++ }, { 1_000 })
        val result = cache.get("key", { text(it).also { value -> require(value == "last-good") } }, { 10 }, fetch = { bytes("bad") })
        assertEquals("last-good", result.data)
        assertEquals(SourceDataCache.Origin.STALE, result.origin)
        assertEquals(0, writes)
    }

    @Test fun concurrentExpiredReadsShareOneRefresh() = runBlocking {
        var saved = SourceDataCache.Entry(bytes("old"), 1)
        var requests = 0
        val cache = SourceDataCache({ saved }, { _, entry -> saved = entry }, { 10_000 })
        val fetch: suspend () -> ByteArray = { requests++; delay(20); bytes("new") }
        val first = async { cache.get("key", ::text, { 5 }, fetch = fetch) }
        val second = async { cache.get("key", ::text, { 5 }, fetch = fetch) }
        assertEquals("new", first.await().data)
        assertEquals("new", second.await().data)
        assertEquals(1, requests)
        assertFalse(saved.savedAt < 10_000)
        assertTrue(second.isCompleted)
    }
}
