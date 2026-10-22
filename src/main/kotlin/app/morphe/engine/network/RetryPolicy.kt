/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.network

import java.io.IOException
import java.nio.file.FileSystemException

/**
 * What the HTTP layer retries, and how long it waits. Pure decisions with no I/O, so the policy is
 * testable directly and documented in one place:
 *
 * **Retried** (up to [MAX_ATTEMPTS] attempts, exponential backoff from [INITIAL_DELAY_MS] capped at
 * [MAX_DELAY_MS]): transport failures (connection reset, DNS, connect/read timeouts, premature
 * close), HTTP 408, 429 (waiting for `Retry-After` when given, within the cap) and 5xx other than
 * 501.
 *
 * **Never retried**: other 4xx (the answer will not change), a response that arrived but cannot be
 * understood (HTML where JSON was expected, invalid JSON: asking again returns the same bytes),
 * an oversized response, local file-system failures, and cancellation.
 */
internal object RetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /** [statusCode] null means no HTTP response was obtained at all (a transport failure). */
    fun isRetryableStatus(statusCode: Int?): Boolean = when {
        statusCode == null -> true
        statusCode == 408 || statusCode == 429 -> true
        statusCode == 501 -> false
        statusCode in 500..599 -> true
        else -> false
    }

    /**
     * Whether [t], thrown while talking to the network, is worth another attempt. Only I/O failures
     * qualify; anything else (a parse error, an illegal state) is a bug or a bad response that a
     * retry cannot fix. Local disk failures are I/O errors too but are not the network's fault.
     */
    fun isTransient(t: Throwable): Boolean = when {
        t is FileSystemException -> false
        t is IOException && t.message?.contains("No space left", ignoreCase = true) == true -> false
        t is IOException -> true
        else -> false
    }

    /** Delay before attempt `attempt + 1`: [retryAfterMs] if the server gave one, else exponential. */
    fun delayAfter(attempt: Int, retryAfterMs: Long? = null): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0, MAX_DELAY_MS)
        var delay = INITIAL_DELAY_MS
        repeat((attempt - 1).coerceAtLeast(0)) { delay = (delay * 2).coerceAtMost(MAX_DELAY_MS) }
        return delay
    }

    /** True when [body] is evidently a web page (an error page, a captive portal, a proxy). */
    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(64).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body")
    }
}
