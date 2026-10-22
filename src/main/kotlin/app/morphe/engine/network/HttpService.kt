/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.network

import app.morphe.engine.GitHubPatMissingException
import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * HTTP client wrapper providing JSON decoding, direct-to-disk streaming downloads,
 * and exponential retry logic for transient errors and HTTP 429.
 */
class HttpService(
    @PublishedApi internal val http: HttpClient,
    @PublishedApi internal val json: Json = Json { ignoreUnknownKeys = true },
    /** Milliseconds to wait before attempt `n + 1` (given a server-supplied Retry-After, if any). Overridable for tests. */
    private val backoffDelay: (attempt: Int, retryAfterMs: Long?) -> Long = RetryPolicy::delayAfter,
) {

    /**
     * GET [url] and decode response body to [T], or return raw string if `T == String`.
     * Decodes text directly so JSON served as `text/plain` works without ContentNegotiation.
     */
    suspend inline fun <reified T> request(
        url: String,
        crossinline builder: HttpRequestBuilder.() -> Unit = {},
    ): T {
        // Only the *fetch* is retried. Decoding happens after the retry loop: a body that arrived
        // but cannot be understood (an HTML error page, a captive portal, invalid JSON) is the same
        // bytes on every attempt, so asking again would only add delay.
        val body = withRetry("request $url") {
            val response: HttpResponse = http.get(url) {
                metadataTimeouts()
                builder()
            }
            response.throwIfError(url)
            response.bodyAsText()
        }
        if (T::class == String::class) {
            @Suppress("UNCHECKED_CAST")
            return body as T
        }
        rejectIfHtml(url, body)
        return try {
            json.decodeFromString(body)
        } catch (e: SerializationException) {
            throw MalformedResponseException(url, "response is not valid JSON for this request", body, e)
        } catch (e: IllegalArgumentException) {
            throw MalformedResponseException(url, "response is not valid JSON for this request", body, e)
        }
    }

    /** Throws [MalformedResponseException] for a web page where JSON was expected.
     *  Separate and `@PublishedApi` because [request] is a public inline function and so cannot
     *  reach the internal [RetryPolicy] directly. */
    @PublishedApi
    internal fun rejectIfHtml(url: String, body: String) {
        if (RetryPolicy.looksLikeHtml(body)) {
            throw MalformedResponseException(url, "received an HTML page instead of JSON", body)
        }
    }

    /** Short, bounded timeouts for small metadata calls. Large downloads keep the client's idle-based
     *  timeouts and deliberately get no total wall-clock cap. */
    @PublishedApi
    internal fun HttpRequestBuilder.metadataTimeouts() {
        timeout {
            connectTimeoutMillis = METADATA_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = METADATA_SOCKET_TIMEOUT_MS
            requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS
        }
    }

    /** HEAD [url], returning response headers while retrying on HTTP 429. */
    suspend fun head(
        url: String,
        builder: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = withRetry("head $url") {
        val response = http.head(url) {
            metadataTimeouts()
            builder()
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw TooManyRequestsException(response.retryAfterMillis())
        }
        response
    }

    /**
     * Download [url] to [saveLocation], streaming straight to disk. The
     * response body is never stored in memory (Pratham's chess patch is 100MB+ and that would
     * cause issues. This fixes it. Writes to a `.part` file and atomically swaps on success;
     * removes the partial file on any failure. Throws on failure after retries.
     *
     * [maxBytes], when set, is enforced twice: immediately against `Content-Length` if the
     * server sends one (fails before a single body byte is read), and again as a running
     * total while streaming (catches a response that lies about or omits `Content-Length`).
     * Exceeding it throws [ResponseTooLargeException], which — unlike a transient network
     * error — is never retried; a response that's too large now will still be too large on
     * attempt 2.
     */
    suspend fun downloadToFile(
        url: String,
        saveLocation: File,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)? = null,
        maxBytes: Long? = null,
        builder: HttpRequestBuilder.() -> Unit = {},
    ): File = downloadLocks.getOrPut(saveLocation.absoluteFile.path) { Mutex() }.withLock {
        // One writer per destination: two callers streaming into the same `.part` would
        // truncate each other's bytes.
        saveLocation.parentFile?.mkdirs()
        val part = File(saveLocation.parentFile, "${saveLocation.name}.part")
        try {
            withRetry("download ${saveLocation.name}") {
                FileOutputStream(part, false).use { out ->
                    http.prepareGet(url) { builder() }.execute { response ->
                        response.throwIfError(url)
                        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        val encoded = response.headers[HttpHeaders.ContentEncoding] != null
                        if (maxBytes != null && contentLength != null && contentLength > maxBytes) {
                            throw ResponseTooLargeException(url, maxBytes)
                        }
                        val copied = withContext(Dispatchers.IO) {
                            response.bodyAsChannel().toInputStream().use { input ->
                                copyStreaming(input, out, contentLength, maxBytes, url, onProgress)
                            }
                        }
                        // A connection that drops mid-body can end the stream early without an
                        // error. Fewer bytes than promised is a truncated file, not a download
                        // (skipped when a Content-Encoding means the length counts encoded bytes).
                        if (!encoded && contentLength != null && copied != contentLength) {
                            throw IOException("incomplete download of $url: got $copied of $contentLength bytes")
                        }
                    }
                }
            }
            return finalizePartFile(part, saveLocation, url)
        } catch (t: Throwable) {
            runCatching { if (part.exists()) part.delete() }
            throw t
        }
    }

    /** Resolves the built patch artifact URL for a GitHub pull request via Actions runs. */
    suspend fun getAssetFromPullRequest(
        owner: String,
        repo: String,
        pullRequestNumber: String,
        pat: String? = null,
    ): GitHubPrAsset {
        val pullUrl = "https://api.github.com/repos/$owner/$repo/pulls/$pullRequestNumber"
        val pull: GitHubPull = request(pullUrl) {
            headers { applyGitHubApiHeaders(pat) }
        }
        val targetSha = pull.head.sha
        if (targetSha.isBlank()) {
            throw IllegalStateException("Failed to resolve HEAD commit SHA for PR #$pullRequestNumber")
        }

        var page = 1
        var matchingRun: GitHubWorkflowRun? = null
        var matchingShaRun: GitHubWorkflowRun? = null

        while (page <= 10) {
            val runsUrl = "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=100&page=$page"
            val runs: GitHubWorkflowRunsResponse = request(runsUrl) {
                headers { applyGitHubApiHeaders(pat) }
            }

            val matchingRuns = runs.workflowRuns.filter { it.headSha == targetSha }
            matchingShaRun = matchingShaRun ?: matchingRuns.firstOrNull()
            matchingRun = matchingRuns.firstOrNull {
                it.status.equals("completed", ignoreCase = true) &&
                    it.conclusion.equals("success", ignoreCase = true)
            }
            if (matchingRun != null) break
            if (runs.workflowRuns.isEmpty()) break
            page++
        }

        val run = matchingRun
            ?: matchingShaRun?.let {
                throw IllegalStateException(
                    "GitHub Actions run for PR #$pullRequestNumber (SHA: $targetSha) is not completed successfully"
                )
            }
            ?: throw IllegalStateException("No GitHub Actions run found for PR #$pullRequestNumber (SHA: $targetSha)")

        val artifactsUrl = "https://api.github.com/repos/$owner/$repo/actions/runs/${run.id}/artifacts"
        val artifactsResponse: GitHubArtifactsResponse = request(artifactsUrl) {
            headers { applyGitHubApiHeaders(pat) }
        }

        val artifact = artifactsResponse.artifacts.firstOrNull {
            it.name.contains("patch", ignoreCase = true) || it.name.endsWith(".mpp", ignoreCase = true)
        } ?: throw IllegalStateException(
            "No patch artifact found for PR #$pullRequestNumber, did the GitHub Action publish the .mpp artifact?"
        )

        return GitHubPrAsset(
            downloadUrl = artifact.archiveDownloadUrl,
            headSha = run.headSha,
            artifactName = artifact.name,
            sizeInBytes = artifact.sizeInBytes,
            createdAt = artifact.createdAt,
            title = run.displayTitle ?: pull.title,
            pageUrl = "https://github.com/$owner/$repo/pull/$pullRequestNumber"
        )
    }

    @Suppress("UastIncorrectHttpHeaderInspection")
    private fun HeadersBuilder.applyGitHubApiHeaders(pat: String?) {
        append(HttpHeaders.Accept, "application/vnd.github+json")
        append("X-GitHub-Api-Version", "2022-11-28")
        if (!pat.isNullOrBlank()) {
            append(HttpHeaders.Authorization, "Bearer $pat")
        }
    }

    /** Downloads a GitHub Actions artifact zip for a PR and extracts the contained `.mpp` bundle. */
    suspend fun downloadPrArtifactToFile(
        url: String,
        saveLocation: File,
        pat: String,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)? = null,
    ): File {
        if (pat.isBlank()) {
            throw GitHubPatMissingException()
        }
        saveLocation.parentFile?.mkdirs()
        val part = File(saveLocation.parentFile, "${saveLocation.name}.part")
        try {
            withRetry("download PR artifact ${saveLocation.name}") {
                FileOutputStream(part, false).use { out ->
                    http.prepareGet(url) {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer $pat")
                            append(HttpHeaders.Accept, "application/vnd.github+json")
                        }
                    }.execute { response ->
                        response.throwIfError(url)
                        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
                        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        val isZip = contentType.contains("zip", ignoreCase = true) || url.endsWith(".zip", ignoreCase = true)

                        withContext(Dispatchers.IO) {
                            if (isZip) {
                                val zis = ZipInputStream(response.bodyAsChannel().toInputStream())
                                zis.use { zip ->
                                    val buffer = ByteArray(64 * 1024)
                                    var copiedBytes = 0L
                                    var lastBytes = 0L
                                    var lastAt = 0L
                                    var foundMpp = false

                                    var entry = zip.nextEntry
                                    while (entry != null) {
                                        if (!entry.isDirectory && entry.name.endsWith(".mpp", ignoreCase = true)) {
                                            foundMpp = true
                                            val entrySize = entry.size.takeIf { it > 0 }
                                            while (true) {
                                                val read = zip.read(buffer)
                                                if (read == -1) break
                                                out.write(buffer, 0, read)
                                                copiedBytes += read
                                                if (onProgress != null) {
                                                    val now = System.currentTimeMillis()
                                                    if (copiedBytes - lastBytes >= PROGRESS_MIN_BYTES || now - lastAt >= PROGRESS_INTERVAL_MS) {
                                                        lastBytes = copiedBytes
                                                        lastAt = now
                                                        onProgress(copiedBytes, entrySize ?: contentLength)
                                                    }
                                                }
                                            }
                                            break
                                        }
                                        zip.closeEntry()
                                        entry = zip.nextEntry
                                    }
                                    if (!foundMpp || copiedBytes == 0L) {
                                        throw IOException("No .mpp file found in the PR artifact")
                                    }
                                    onProgress?.invoke(copiedBytes, copiedBytes)
                                }
                            } else {
                                response.bodyAsChannel().toInputStream().use { input ->
                                    copyStreaming(input, out, null, contentLength, url, onProgress)
                                }
                            }
                        }
                    }
                }
            }
            return finalizePartFile(part, saveLocation, url)
        } catch (t: Throwable) {
            runCatching { if (part.exists()) part.delete() }
            throw t
        }
    }

    private fun finalizePartFile(part: File, saveLocation: File, url: String): File {
        if (part.length() == 0L) throw HttpException(null, url, "download returned 0 bytes")
        // Replace in one step. The previous file is never deleted first, so a failed swap
        // leaves the last valid destination exactly as it was.
        AtomicFiles.replaceFile(part, saveLocation)
        return saveLocation
    }

    /**
     * Copy [input] → [output] in 64 KB chunks (constant memory), reporting byte
     * progress. Progress is throttled: at most once per [PROGRESS_MIN_BYTES] or
     * [PROGRESS_INTERVAL_MS], whichever first, plus a guaranteed final call.
     * Aborts via [ResponseTooLargeException] the moment the running total would
     * exceed [maxBytes], before that final chunk is written — so a too-large
     * response is never fully buffered to disk just to be deleted afterward.
     */
    private fun copyStreaming(
        input: InputStream,
        output: OutputStream,
        contentLength: Long?,
        maxBytes: Long?,
        url: String,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)?,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var lastBytes = 0L
        var lastAt = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (maxBytes != null && total > maxBytes) {
                throw ResponseTooLargeException(url, maxBytes)
            }
            output.write(buffer, 0, read)
            if (onProgress != null) {
                val now = System.currentTimeMillis()
                if (total - lastBytes >= PROGRESS_MIN_BYTES || now - lastAt >= PROGRESS_INTERVAL_MS) {
                    lastBytes = total
                    lastAt = now
                    onProgress(total, contentLength)
                }
            }
        }
        onProgress?.invoke(total, contentLength)
        return total
    }

    /** Executes [block] with exponential backoff on transient errors and HTTP 429. */
    @PublishedApi
    internal suspend fun <T> withRetry(operation: String, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (t: CancellationException) {
                throw t
            } catch (t: ResponseTooLargeException) {
                // Deterministic, not transient: the response will still be too large
                // on the next attempt, so retrying would just waste 3x the bandwidth.
                throw t
            } catch (t: TooManyRequestsException) {
                if (attempt >= RetryPolicy.MAX_ATTEMPTS) {
                    throw HttpException(HttpStatusCode.TooManyRequests, operation, cause = t)
                }
                val wait = backoffDelay(attempt, t.retryAfterMillis)
                Logger.warn("$operation hit 429 (attempt $attempt/${RetryPolicy.MAX_ATTEMPTS}), waiting ${wait}ms")
                delay(wait.milliseconds)
            } catch (t: HttpException) {
                if (!t.isRetryable || attempt >= RetryPolicy.MAX_ATTEMPTS) throw t
                Logger.warn("$operation attempt $attempt: retryable HTTP error: ${t.message}")
                delay(backoffDelay(attempt, null).milliseconds)
            } catch (t: MalformedResponseException) {
                throw t   // the same bytes would come back
            } catch (t: Exception) {
                // Only network I/O trouble is worth another attempt. A parse failure, an illegal
                // state or a local disk error is surfaced immediately instead of being retried.
                if (!RetryPolicy.isTransient(t) || attempt >= RetryPolicy.MAX_ATTEMPTS) throw t
                Logger.warn("$operation attempt $attempt failed: ${t::class.simpleName}: ${t.message}")
                delay(backoffDelay(attempt, null).milliseconds)
            }
        }
    }

    @PublishedApi
    internal suspend fun HttpResponse.throwIfError(url: String) {
        if (status == HttpStatusCode.TooManyRequests) throw TooManyRequestsException(retryAfterMillis())
        if (!status.isSuccess()) {
            val body = runCatching { bodyAsText() }.getOrNull()
            throw HttpException(status, url, body)
        }
    }

    @PublishedApi
    internal fun HttpResponse.retryAfterMillis(): Long? =
        headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceAtLeast(0)?.times(1000)

    /** HTTP error preserving status, request URL, and response snippet for diagnostics. */
    class HttpException(
        val status: HttpStatusCode?,
        val requestUrl: String? = null,
        val responseBodySnippet: String? = null,
        cause: Throwable? = null,
    ) : Exception(
        buildString {
            append("HTTP request failed")
            if (status != null) append(" with status $status")
            if (requestUrl != null) append(" for $requestUrl")
            if (responseBodySnippet != null) {
                val trimmed = responseBodySnippet.trim()
                val snippet = if (trimmed.startsWith("<")) {
                    Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.get(1)?.trim()
                        ?: "HTML response"
                } else {
                    val formatted = runCatching {
                        Json.parseToJsonElement(trimmed).toString()
                    }.getOrElse {
                        trimmed.replace(Regex("\\s+"), " ")
                    }
                    formatted.take(300)
                }
                append(": $snippet")
            }
        },
        cause,
    ) {
        val isRetryable: Boolean
            get() = RetryPolicy.isRetryableStatus(status?.value)
    }

    class TooManyRequestsException(val retryAfterMillis: Long?) :
        Exception("HTTP 429 Too Many Requests")

    /**
     * A response arrived (status 2xx) but is not what the caller needs: an HTML page where JSON was
     * expected, or JSON of the wrong shape. Never retried, because the same request returns the same
     * bytes. [detail] is a concise diagnosis; the body is not included beyond a short snippet.
     */
    class MalformedResponseException(
        val requestUrl: String,
        val detail: String,
        body: String? = null,
        cause: Throwable? = null,
    ) : Exception(
        buildString {
            append("Unusable response from $requestUrl: $detail")
            val title = body?.let { Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
            if (!title.isNullOrBlank()) append(" (page title: ${title.take(80)})")
        },
        cause,
    )

    /** Thrown by [downloadToFile] when a response exceeds a caller-supplied [maxBytes]. */
    class ResponseTooLargeException(val requestUrl: String, val limitBytes: Long) :
        Exception("Response from $requestUrl exceeded the $limitBytes byte limit")

    companion object {
        /** Metadata/API calls: fail fast (see [RetryPolicy] for what is retried and how often). */
        @PublishedApi internal const val METADATA_CONNECT_TIMEOUT_MS = 8_000L
        @PublishedApi internal const val METADATA_SOCKET_TIMEOUT_MS = 10_000L
        @PublishedApi internal const val METADATA_REQUEST_TIMEOUT_MS = 20_000L

        /** One lock per download destination, shared by every [HttpService] instance. */
        private val downloadLocks = ConcurrentHashMap<String, Mutex>()

        private const val PROGRESS_MIN_BYTES = 64 * 1024L
        private const val PROGRESS_INTERVAL_MS = 200L
    }
}

@Serializable
data class GitHubPrAsset(
    val downloadUrl: String,
    val headSha: String,
    val artifactName: String,
    val sizeInBytes: Long = 0L,
    val createdAt: String? = null,
    val title: String? = null,
    val pageUrl: String? = null,
)

@Serializable
private data class GitHubPullHead(
    val sha: String = "",
)

@Serializable
private data class GitHubPull(
    val head: GitHubPullHead = GitHubPullHead(),
    val title: String? = null,
)

@Serializable
private data class GitHubWorkflowRun(
    val id: Long = 0L,
    @SerialName("head_sha") val headSha: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
)

@Serializable
private data class GitHubWorkflowRunsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("workflow_runs") val workflowRuns: List<GitHubWorkflowRun> = emptyList(),
)

@Serializable
private data class GitHubArtifact(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("size_in_bytes") val sizeInBytes: Long = 0L,
    @SerialName("archive_download_url") val archiveDownloadUrl: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class GitHubArtifactsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    val artifacts: List<GitHubArtifact> = emptyList(),
)
