/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import app.morphe.engine.network.HttpService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Resolution, caching and failure policy of [AppIconRepository], driven through a MockEngine with
 * an injected clock. No test talks to Google; the listing HTML is fixture markup (see [PlayListingTest]).
 */
class AppIconRepositoryTest {

    private val now = AtomicLong(1_000_000L)
    private val dir: File = createTempDirectory("app-icon-repo-test").toFile()

    private val png: ByteArray = pngOfSize(32)

    private fun pngOfSize(size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until size) for (y in 0 until size) image.setRGB(x, y, 0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun iconUrl(pkg: String) = "https://play-lh.googleusercontent.com/$pkg-icon=s256-rw"

    private fun listingHtml(pkg: String, image: String = iconUrl(pkg)) =
        """<html><script type="application/ld+json">{"@type":"SoftwareApplication","name":"$pkg","image":"$image"}</script></html>"""

    /** Recorded requests, split by who was asked. */
    private class Traffic {
        val listings = CopyOnWriteArrayList<String>()
        val images = CopyOnWriteArrayList<String>()
        val total get() = listings.size + images.size
    }

    private fun MockRequestHandleScope.html(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))

    private fun MockRequestHandleScope.image(body: ByteArray) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))

    /** Well-behaved Play: a listing for every package, one valid PNG for every icon. */
    private fun happyPlay(): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        when (request.url.host) {
            "play.google.com" -> html(listingHtml(request.url.parameters["id"]!!))
            else -> image(png)
        }
    }

    private fun repository(
        traffic: Traffic,
        maxConcurrent: Int = 3,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = happyPlay(),
    ): AppIconRepository {
        val engine = MockEngine { request ->
            when (request.url.host) {
                "play.google.com" -> traffic.listings += request.url.parameters["id"].orEmpty()
                else -> traffic.images += request.url.toString()
            }
            handler(this, request)
        }
        val http = HttpService(HttpClient(engine), backoffDelay = { _, _ -> 0L })
        return AppIconRepository(
            http = http,
            cache = AppIconCache(dir) { now.get() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            maxConcurrent = maxConcurrent,
        )
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun cachedFiles() = dir.listFiles().orEmpty().filter { it.extension == "img" }

    // ---- resolution and persistence ----

    @Test
    fun `a package resolves through its listing and the icon is cached on disk`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic)

        assertNull(repo.peek("com.example.app"))
        val icon = repo.load("com.example.app")

        assertNotNull(icon)
        assertEquals(32, icon.width)
        assertEquals(listOf("com.example.app"), traffic.listings.toList())
        assertEquals(listOf(iconUrl("com.example.app")), traffic.images.toList())
        assertNotNull(repo.peek("com.example.app"), "once resolved the icon is available to composition without suspending")
        assertContentEquals(png, AppIconCache(dir).readIcon("com.example.app"))
        assertTrue(dir.listFiles().orEmpty().none { it.extension in setOf("dl", "html", "part") }, "no scratch files are left behind")
    }

    @Test
    fun `a cached icon is loaded after a restart without any request`() = blocking {
        repository(Traffic()).load("com.example.app")

        val afterRestart = Traffic()
        val icon = repository(afterRestart).load("com.example.app")

        assertNotNull(icon)
        assertEquals(0, afterRestart.total)
    }

    @Test
    fun `a corrupt cached file is discarded and resolved again`() = blocking {
        repository(Traffic()).load("com.example.app")
        AppIconCache(dir).iconFile("com.example.app").writeBytes(byteArrayOf(1, 2, 3))

        val traffic = Traffic()
        val icon = repository(traffic).load("com.example.app")

        assertNotNull(icon)
        assertEquals(1, traffic.listings.size)
        assertContentEquals(png, AppIconCache(dir).readIcon("com.example.app"))
    }

    @Test
    fun `invalidateAll forgets decoded icons so a cleared cache is fetched again`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic)
        repo.load("com.example.app")

        dir.listFiles().orEmpty().forEach { it.delete() }
        repo.invalidateAll()

        assertNull(repo.peek("com.example.app"))
        assertNotNull(repo.load("com.example.app"))
        assertEquals(2, traffic.listings.size)
    }

    // ---- invalid input ----

    @Test
    fun `invalid package ids make no request`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic)

        listOf("", "nodots", "has space.app", "../../x.y", "a.b&c=d").forEach { assertNull(repo.load(it), it) }

        assertEquals(0, traffic.total)
    }

    // ---- failures ----

    @Test
    fun `a missing listing is unresolved and not asked about again after a restart`() = blocking {
        val first = Traffic()
        val notFound: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { html("", HttpStatusCode.NotFound) }

        assertNull(repository(first, handler = notFound).load("com.example.gone"))
        assertEquals(1, first.listings.size, "a 404 is not retried")

        now.addAndGet(60_000)
        val restarted = Traffic()
        assertNull(repository(restarted, handler = notFound).load("com.example.gone"))
        assertEquals(0, restarted.total, "the cooldown outlives the process")

        now.addAndGet(IconFailure.NO_LISTING.baseCooldownMs)
        val afterCooldown = Traffic()
        repository(afterCooldown, handler = notFound).load("com.example.gone")
        assertEquals(1, afterCooldown.listings.size)
    }

    @Test
    fun `a listing without icon metadata is unresolved and downloads nothing`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic) { html("<html><body>changed markup</body></html>") }

        assertNull(repo.load("com.example.app"))
        assertEquals(0, traffic.images.size)
        assertTrue(cachedFiles().isEmpty())
    }

    @Test
    fun `an icon url on another host is never downloaded`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic) { html(listingHtml("com.example.app", "https://evil.example/icon.png")) }

        assertNull(repo.load("com.example.app"))
        assertEquals(0, traffic.images.size)
    }

    @Test
    fun `a challenge page is a blocked result with a cooldown, not a retry loop`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic) { html("<form id=\"captcha-form\" action=\"/recaptcha/api\"></form>") }

        assertNull(repo.load("com.example.app"))
        assertEquals(0, traffic.images.size)

        val again = Traffic()
        assertNull(repository(again) { html("<form id=\"captcha-form\"></form>") }.load("com.example.app"))
        assertEquals(0, again.total)
    }

    @Test
    fun `403 is blocked and a server error is transient, neither crashes`() = blocking {
        val forbidden = repository(Traffic()) { html("", HttpStatusCode.Forbidden) }
        val broken = repository(Traffic()) { html("", HttpStatusCode.InternalServerError) }

        assertNull(forbidden.load("com.example.a1"))
        assertNull(broken.load("com.example.b1"))
    }

    @Test
    fun `a transient failure is retried after its cooldown`() = blocking {
        val traffic = Traffic()
        val serverError: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { html("", HttpStatusCode.ServiceUnavailable) }
        assertNull(repository(traffic, handler = serverError).load("com.example.app"))
        assertTrue(traffic.listings.isNotEmpty())

        // Still cooling down: no request at all.
        now.addAndGet(IconFailure.TRANSIENT.baseCooldownMs / 2)
        val during = Traffic()
        assertNull(repository(during, handler = serverError).load("com.example.app"))
        assertEquals(0, during.total)

        now.addAndGet(IconFailure.TRANSIENT.baseCooldownMs)
        val healthy = Traffic()
        assertNotNull(repository(healthy).load("com.example.app"))
        assertEquals(1, healthy.listings.size)
    }

    @Test
    fun `Retry-After from a 429 extends the cooldown past the default backoff`() = blocking {
        val limited: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7200"))
        }
        assertNull(repository(Traffic(), handler = limited).load("com.example.app"))

        now.addAndGet(IconFailure.TRANSIENT.baseCooldownMs + 1)
        val stillLimited = Traffic()
        assertNull(repository(stillLimited, handler = limited).load("com.example.app"))
        assertEquals(0, stillLimited.total, "Retry-After of two hours outranks the 15 minute backoff")

        now.addAndGet(2 * 60 * 60_000L)
        val recovered = Traffic()
        assertNotNull(repository(recovered).load("com.example.app"))
    }

    @Test
    fun `empty, non-image, tiny and oversized downloads never become a cache entry`() = blocking {
        val bodies = mapOf(
            "empty" to ByteArray(0),
            "text" to "definitely not an image".toByteArray(),
            "tiny" to pngOfSize(4),
            "oversized" to ByteArray(2 * 1024 * 1024) { 7 },
        )
        for ((name, body) in bodies) {
            val repo = repository(Traffic()) { request ->
                if (request.url.host == "play.google.com") html(listingHtml("com.example.$name")) else image(body)
            }
            assertNull(repo.load("com.example.$name"), name)
            assertTrue(cachedFiles().isEmpty(), "$name must not leave a cache entry")
            assertTrue(dir.listFiles().orEmpty().none { it.extension in setOf("dl", "part") }, "$name must not leave scratch files")
        }
    }

    @Test
    fun `a failed commit leaves the previous valid icon untouched`() {
        val cache = AppIconCache(dir)
        cache.downloadFile("com.example.app").writeBytes(png)
        cache.commit("com.example.app", cache.downloadFile("com.example.app"))

        assertFailsWith<java.io.IOException> { cache.commit("com.example.app", File(dir, "does-not-exist.dl")) }

        assertContentEquals(png, cache.readIcon("com.example.app"))
    }

    // ---- sharing and bounds ----

    @Test
    fun `concurrent requests for one package share a single lookup`() = blocking {
        val traffic = Traffic()
        val gate = CompletableDeferred<Unit>()
        val repo = repository(traffic) { request ->
            if (request.url.host == "play.google.com") {
                gate.await()
                html(listingHtml(request.url.parameters["id"]!!))
            } else {
                image(png)
            }
        }

        val callers = List(6) { async(Dispatchers.IO) { repo.load("com.example.app") } }
        while (traffic.listings.isEmpty()) delay(5)
        delay(50)
        gate.complete(Unit)

        assertTrue(callers.awaitAll().all { it != null })
        assertEquals(1, traffic.listings.size)
        assertEquals(1, traffic.images.size)
    }

    @Test
    fun `a caller that gives up does not cancel the lookup others are waiting on`() = blocking {
        val traffic = Traffic()
        val gate = CompletableDeferred<Unit>()
        val repo = repository(traffic) { request ->
            if (request.url.host == "play.google.com") {
                gate.await()
                html(listingHtml(request.url.parameters["id"]!!))
            } else {
                image(png)
            }
        }

        val leaving = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.load("com.example.app") }
        while (traffic.listings.isEmpty()) delay(5)
        leaving.cancel(CancellationException("card left the screen"))
        val staying = async(Dispatchers.IO) { repo.load("com.example.app") }
        delay(50)
        gate.complete(Unit)

        assertNotNull(staying.await())
        assertEquals(1, traffic.listings.size)
    }

    @Test
    fun `distinct packages are independent and lookups stay within the concurrency bound`() = blocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val repo = repository(Traffic(), maxConcurrent = 3) { request ->
            if (request.url.host == "play.google.com") {
                val id = request.url.parameters["id"]!!
                val n = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, n) }
                delay(80)
                active.decrementAndGet()
                if (id == "com.example.broken") html("", HttpStatusCode.NotFound) else html(listingHtml(id))
            } else {
                image(png)
            }
        }

        val names = (1..9).map { "com.example.app$it" } + "com.example.broken"
        val results = names.map { async(Dispatchers.IO) { it to repo.load(it) } }.awaitAll().toMap()

        assertNull(results["com.example.broken"], "one package failing does not affect the others")
        assertEquals(9, results.values.count { it != null })
        assertTrue(peak.get() <= 3, "at most three lookups at a time, saw ${peak.get()}")
    }

    @Test
    fun `prefetch warms only a bounded number of packages`() = blocking {
        val traffic = Traffic()
        val repo = repository(traffic)

        repo.prefetch((1..40).map { "com.example.app$it" })
        while (traffic.listings.size < 12) delay(10)
        delay(200)

        assertEquals(12, traffic.listings.size)
        assertFalse(traffic.listings.contains("com.example.app13"))
    }
}
