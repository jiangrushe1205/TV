package cn.v65.live.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SourceClientTest {
    @Test fun prefixReadIsBoundedWhenServerIgnoresRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("123456789"))
            assertEquals("123", SourceClient().getBytes(server.url("/segment").toString(), maxBytes = 3, prefixOnly = true).toString(Charsets.UTF_8))
        }
    }
    @Test fun forwardsOnlyRequestedHeadersAndReceivesBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("official data"))
            val response = SourceClient().getBytes(server.url("/catalog").toString(), mapOf("Referer" to "https://www.yangshipin.cn/"))
            assertEquals("official data", response.toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("https://www.yangshipin.cn/", request.getHeader("Referer"))
            assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun rejectsOversizedAndFailedResponsesThenCanRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("12345"))
            server.enqueue(MockResponse().setBody("ok"))
            val client = SourceClient()
            assertTrue(runCatching { client.getBytes(server.url("/denied").toString()) }.isFailure)
            assertTrue(runCatching { client.getBytes(server.url("/oversized").toString(), maxBytes = 3) }.isFailure)
            assertEquals("ok", client.getBytes(server.url("/retry").toString()).toString(Charsets.UTF_8))
        }
    }
    @Test fun cancelsAnObsoleteChannelRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old channel").setBodyDelay(2, TimeUnit.SECONDS))
            val pending = async { SourceClient().getBytes(server.url("/slow").toString()) }
            delay(150)
            pending.cancel()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        }
    }
}
