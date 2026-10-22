/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HttpService's failure policy, driven through a deterministic MockEngine: which failures are
 * retried (and how often), which are not, and that a failed download never damages the last
 * valid destination file. Backoff is injected as zero so retry counts are asserted without sleeping.
 */
class HttpServiceTest {

    @Serializable
    private data class Payload(val value: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private val delays = mutableListOf<Pair<Int, Long?>>()

    private fun service(
        calls: AtomicInteger,
        handler: suspend MockRequestHandleScope.(Int) -> HttpResponseData,
    ): HttpService {
        val engine = MockEngine { handler(this, calls.incrementAndGet()) }
        return HttpService(HttpClient(engine), json, backoffDelay = { attempt, retryAfter -> delays += attempt to retryAfter; 0L })
    }

    private fun MockRequestHandleScope.text(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun dir(): File = createTempDirectory("http-service-test").toFile()

    // ---- request(): decoding ----

    @Test
    fun `a valid json body decodes`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { text("""{"value": 7}""") }
        assertEquals(7, http.request<Payload>("https://example.test/x").value)
        assertEquals(1, calls.get())
    }

    @Test
    fun `an html page instead of json fails immediately and is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { text("<!DOCTYPE html><html><head><title>Maintenance</title></head></html>") }
        val e = assertFailsWith<HttpService.MalformedResponseException> { http.request<Payload>("https://example.test/x") }
        assertEquals(1, calls.get(), "the same HTML would come back; retrying only adds delay")
        assertTrue("HTML" in e.message.orEmpty() && "Maintenance" in e.message.orEmpty(), e.message)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `invalid json is a malformed response and is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { text("{ not json") }
        assertFailsWith<HttpService.MalformedResponseException> { http.request<Payload>("https://example.test/x") }
        assertEquals(1, calls.get())
    }

    // ---- request(): status handling ----

    @Test
    fun `a 404 is permanent and is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { text("not found", HttpStatusCode.NotFound) }
        val e = assertFailsWith<HttpService.HttpException> { http.request<Payload>("https://example.test/x") }
        assertEquals(HttpStatusCode.NotFound, e.status)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a persistent 500 is retried up to the limit then reported`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { text("boom", HttpStatusCode.InternalServerError) }
        val e = assertFailsWith<HttpService.HttpException> { http.request<Payload>("https://example.test/x") }
        assertEquals(HttpStatusCode.InternalServerError, e.status)
        assertEquals(RetryPolicy.MAX_ATTEMPTS, calls.get())
    }

    @Test
    fun `a transient 503 that clears is recovered from`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { n -> if (n < 2) text("busy", HttpStatusCode.ServiceUnavailable) else text("""{"value": 1}""") }
        assertEquals(1, http.request<Payload>("https://example.test/x").value)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a 429 honours Retry-After`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { n ->
            if (n < 2) respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
            else text("""{"value": 2}""")
        }
        assertEquals(2, http.request<Payload>("https://example.test/x").value)
        assertEquals(listOf<Pair<Int, Long?>>(1 to 3_000L), delays, "the server's Retry-After (3s) must be the wait that was requested")
    }

    // ---- request(): transport failures ----

    @Test
    fun `a connection reset is retried and recovers`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { n -> if (n == 1) throw IOException("Connection reset") else text("""{"value": 3}""") }
        assertEquals(3, http.request<Payload>("https://example.test/x").value)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a persistent connection failure stops after the attempt limit`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { throw IOException("Connection reset") }
        assertFailsWith<IOException> { http.request<Payload>("https://example.test/x") }
        assertEquals(RetryPolicy.MAX_ATTEMPTS, calls.get())
    }

    @Test
    fun `a non-network failure is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val http = service(calls) { throw IllegalStateException("bug") }
        assertFailsWith<IllegalStateException> { http.request<Payload>("https://example.test/x") }
        assertEquals(1, calls.get())
    }

    @Test
    fun `cancellation stays cancellation and is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val http = service(calls) { started.complete(Unit); CompletableDeferred<Unit>().await(); text("{}") }
        val job = async(start = CoroutineStart.UNDISPATCHED) { http.request<Payload>("https://example.test/x") }
        started.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, calls.get())
    }

    // ---- downloadToFile ----

    @Test
    fun `a download writes the file and leaves no part file`() = runBlocking {
        val d = dir(); val target = File(d, "bundle.mpp")
        val http = service(AtomicInteger()) { respond("payload-bytes", HttpStatusCode.OK) }
        http.downloadToFile("https://example.test/b", target)
        assertEquals("payload-bytes", target.readText())
        assertEquals(listOf("bundle.mpp"), d.list()!!.toList())
    }

    @Test
    fun `a failed download keeps the previous valid file`() = runBlocking {
        val d = dir(); val target = File(d, "bundle.mpp").apply { writeText("last valid file") }
        val calls = AtomicInteger()
        val http = service(calls) { text("down", HttpStatusCode.ServiceUnavailable) }
        assertFailsWith<HttpService.HttpException> { http.downloadToFile("https://example.test/b", target) }
        assertEquals("last valid file", target.readText(), "the old file must not be deleted before a replacement exists")
        assertEquals(RetryPolicy.MAX_ATTEMPTS, calls.get())
        assertFalse(File(d, "bundle.mpp.part").exists(), "the partial file must be cleaned up")
    }

    @Test
    fun `a truncated body is not accepted as a download and keeps the previous file`() = runBlocking {
        val d = dir(); val target = File(d, "bundle.mpp").apply { writeText("last valid file") }
        val http = service(AtomicInteger()) {
            respond("short", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5000"))
        }
        assertFailsWith<IOException> { http.downloadToFile("https://example.test/b", target) }
        assertEquals("last valid file", target.readText())
    }

    @Test
    fun `a zero byte download is rejected and keeps the previous file`() = runBlocking {
        val d = dir(); val target = File(d, "bundle.mpp").apply { writeText("last valid file") }
        val http = service(AtomicInteger()) { respond("", HttpStatusCode.OK) }
        assertFailsWith<HttpService.HttpException> { http.downloadToFile("https://example.test/b", target) }
        assertEquals("last valid file", target.readText())
    }

    @Test
    fun `an oversized download fails fast, is not retried and writes nothing`() = runBlocking {
        val d = dir(); val target = File(d, "avatar.png")
        val calls = AtomicInteger()
        val http = service(calls) { respond("x".repeat(500), HttpStatusCode.OK) }
        assertFailsWith<HttpService.ResponseTooLargeException> { http.downloadToFile("https://example.test/a", target, maxBytes = 100) }
        assertEquals(1, calls.get())
        assertFalse(target.exists())
        assertEquals(emptyList(), d.list()!!.toList())
    }

    @Test
    fun `a failed final replacement surfaces the error and cleans the part file`() = runBlocking {
        val d = dir()
        val target = File(d, "bundle.mpp").apply { mkdirs(); File(this, "child").writeText("x") }   // cannot be replaced by a file
        val http = service(AtomicInteger()) { respond("payload", HttpStatusCode.OK) }
        assertFailsWith<IOException> { http.downloadToFile("https://example.test/b", target) }
        assertTrue(File(target, "child").exists(), "the existing destination must be untouched")
        assertFalse(File(d, "bundle.mpp.part").exists())
    }
}
