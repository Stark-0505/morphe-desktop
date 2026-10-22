/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.morphe.engine.MorpheData
import app.morphe.engine.network.HttpService
import app.morphe.engine.util.Logger
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * App icons for Morphe's app cards, resolved from public Google Play listings and cached on disk.
 *
 * Per package id: the cached image if there is one; otherwise, unless a recent failure is still
 * cooling down, the public listing page is fetched, the icon URL is read out of it
 * ([PlayListing]), the image is downloaded from Google's image host, validated by decoding it, and
 * moved into the cache. There is no other icon source: a package Play cannot serve stays
 * unresolved and the card keeps its initials.
 *
 * Network work is bounded (three lookups at a time) and shared: callers asking for the same
 * package while a lookup is running await that one lookup. The lookup runs in this repository's
 * own scope, so a card leaving the screen never cancels a download another card is waiting on.
 * Retry/backoff for individual requests is [HttpService]'s; this class adds only the persisted
 * per-package cooldown ([AppIconCache]).
 *
 * The shared client follows redirects without exposing where they ended, so a response is not
 * judged by its origin. It is trusted only once it fits the byte cap and decodes as an image.
 */
class AppIconRepository internal constructor(
    private val http: HttpService,
    private val cache: AppIconCache,
    private val scope: CoroutineScope,
    maxConcurrent: Int,
) {

    constructor(http: HttpService, dir: File = MorpheData.appIconsDir) : this(
        http = http,
        cache = AppIconCache(dir),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        maxConcurrent = MAX_CONCURRENT_LOOKUPS,
    )

    private val permits = Semaphore(maxConcurrent)
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** The icon if it is already decoded in memory. Cheap and non-suspending: safe from composition. */
    fun peek(packageId: String): ImageBitmap? = memory[packageId]

    /**
     * The icon for [packageId], or null when it cannot be resolved right now. Only cancellation of
     * the caller is ever thrown. An invalid id returns null without any I/O.
     */
    suspend fun load(packageId: String): ImageBitmap? {
        if (!PlayListing.isValidPackageId(packageId)) return null
        memory[packageId]?.let { return it }
        val lookup = inFlight.computeIfAbsent(packageId) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    resolve(packageId)
                } finally {
                    inFlight.remove(packageId)
                }
            }
        }
        lookup.start()
        return lookup.await()
    }

    /** Warms the first [PREFETCH_LIMIT] of [packageIds] in the background; the rest load on demand. */
    fun prefetch(packageIds: Collection<String>) {
        val wanted = packageIds.asSequence().distinct().filter { memory[it] == null }.take(PREFETCH_LIMIT).toList()
        if (wanted.isEmpty()) return
        scope.launch { wanted.forEach { load(it) } }
    }

    /** Drops everything held in memory; call after the on-disk cache was cleared. */
    fun invalidateAll() {
        memory.clear()
        cache.reset()
    }

    private suspend fun resolve(packageId: String): ImageBitmap? {
        try {
            readCached(packageId)?.let { return it }
            if (cache.activeFailure(packageId) != null) return null
            val bitmap = permits.withPermit { fetchAndStore(packageId) }
            memory[packageId] = bitmap
            return bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = classify(e)
            Logger.warn("No app icon for $packageId (${failure.kind}): ${e.message}")
            runCatching { cache.recordFailure(packageId, failure.kind, failure.retryAfterMs) }
            return null
        }
    }

    private suspend fun readCached(packageId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = cache.readIcon(packageId) ?: return@withContext null
        val bitmap = decode(bytes)
        if (bitmap != null) {
            memory[packageId] = bitmap
        } else {
            Logger.warn("Discarding unreadable cached app icon for $packageId")
            cache.deleteIcon(packageId)
        }
        bitmap
    }

    private suspend fun fetchAndStore(packageId: String): ImageBitmap {
        val listingUrl = PlayListing.listingUrl(packageId)
            ?: throw Failure(IconFailure.NO_LISTING, "invalid package id")
        val html = fetchListing(packageId, listingUrl)
        val iconUrl = PlayListing.parseIconUrl(html)
            ?: throw Failure(
                if (PlayListing.looksBlocked(html)) IconFailure.BLOCKED else IconFailure.NO_ICON,
                "listing has no recognizable app icon",
            )

        // Download beside the cache entry, validate, then move it over: a bad or partial
        // download can never replace a previously valid icon.
        val download = cache.downloadFile(packageId)
        try {
            http.downloadToFile(iconUrl, download, maxBytes = MAX_ICON_BYTES)
            val bitmap = withContext(Dispatchers.IO) { decode(download.readBytes()) }
                ?: throw Failure(IconFailure.NO_ICON, "downloaded file is not a usable image")
            withContext(Dispatchers.IO) { cache.commit(packageId, download) }
            return bitmap
        } finally {
            runCatching { download.delete() }
        }
    }

    private suspend fun fetchListing(packageId: String, url: String): String {
        val file = cache.listingFile(packageId)
        try {
            http.downloadToFile(url, file, maxBytes = MAX_LISTING_BYTES) {
                header(HttpHeaders.Accept, "text/html")
                header(HttpHeaders.AcceptLanguage, "en")
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
            return withContext(Dispatchers.IO) { file.readText() }
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null
        return try {
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
                .takeIf { it.width in MIN_EDGE..MAX_EDGE && it.height in MIN_EDGE..MAX_EDGE }
        } catch (_: Exception) {
            null
        }
    }

    private class Failure(val kind: IconFailure, message: String, val retryAfterMs: Long? = null) : Exception(message)

    private fun classify(e: Exception): Failure = when (e) {
        is Failure -> e
        is HttpService.HttpException -> when (val code = e.status?.value) {
            404, 410 -> Failure(IconFailure.NO_LISTING, "HTTP $code")
            403 -> Failure(IconFailure.BLOCKED, "HTTP 403")
            HttpStatusCode.TooManyRequests.value -> Failure(
                IconFailure.TRANSIENT, "HTTP 429",
                (e.cause as? HttpService.TooManyRequestsException)?.retryAfterMillis,
            )
            null, in 500..599 -> Failure(IconFailure.TRANSIENT, "HTTP ${code ?: "without a status"}")
            else -> Failure(IconFailure.NO_LISTING, "HTTP $code")
        }
        is HttpService.MalformedResponseException, is HttpService.ResponseTooLargeException ->
            Failure(IconFailure.NO_ICON, e.message.orEmpty())
        // Offline, timeouts, resets and local disk trouble: all worth a later retry, none permanent.
        else -> Failure(IconFailure.TRANSIENT, e.message.orEmpty())
    }

    private companion object {
        /** Conservative on purpose: this is a public web page, not an API. */
        const val MAX_CONCURRENT_LOOKUPS = 3
        const val PREFETCH_LIMIT = 12
        const val MAX_LISTING_BYTES = 4L * 1024 * 1024
        const val MAX_ICON_BYTES = 1L * 1024 * 1024
        const val MIN_EDGE = 16
        const val MAX_EDGE = 4096
        const val USER_AGENT = "Morphe-Desktop (+https://github.com/MorpheApp/morphe-desktop)"
    }
}
