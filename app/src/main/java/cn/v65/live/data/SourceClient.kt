package cn.v65.live.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SourceClient {
    val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 2 * 1024 * 1024, prefixOnly: Boolean = false): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply {
                header("Accept", "*/*")
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("网络请求失败，请检查网络后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw IOException("官方服务器返回 HTTP ${response.code}")
                            val body = response.body ?: throw IOException("服务器返回空响应")
                            if (!prefixOnly && body.contentLength() > maxBytes) throw IOException("数据响应过大")
                            val source = body.source()
                            source.request(if (prefixOnly) maxBytes else maxBytes + 1)
                            if (!prefixOnly && source.buffer.size > maxBytes) throw IOException("数据响应过大")
                            val bytes = source.readByteArray(minOf(source.buffer.size, maxBytes))
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
}
