/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `transient statuses are retried`() {
        for (code in listOf(408, 429, 500, 502, 503, 504, 599)) {
            assertTrue(RetryPolicy.isRetryableStatus(code), "HTTP $code should be retried")
        }
        assertTrue(RetryPolicy.isRetryableStatus(null), "no response at all is a transport failure")
    }

    @Test
    fun `permanent statuses are not retried`() {
        for (code in listOf(200, 301, 400, 401, 403, 404, 410, 422, 501)) {
            assertFalse(RetryPolicy.isRetryableStatus(code), "HTTP $code must not be retried")
        }
    }

    @Test
    fun `network I-O failures are transient`() {
        assertTrue(RetryPolicy.isTransient(ConnectException("refused")))
        assertTrue(RetryPolicy.isTransient(SocketTimeoutException("read timed out")))
        assertTrue(RetryPolicy.isTransient(UnknownHostException("host")))
        assertTrue(RetryPolicy.isTransient(IOException("Connection reset")))
    }

    @Test
    fun `local disk failures and non-IO errors are not transient`() {
        assertFalse(RetryPolicy.isTransient(NoSuchFileException("/x")))
        assertFalse(RetryPolicy.isTransient(AccessDeniedException("/x")))
        assertFalse(RetryPolicy.isTransient(IOException("No space left on device")))
        assertFalse(RetryPolicy.isTransient(IllegalStateException("bug")))
        assertFalse(RetryPolicy.isTransient(IllegalArgumentException("bad json")))
    }

    @Test
    fun `backoff doubles and is capped`() {
        assertEquals(1_000L, RetryPolicy.delayAfter(1))
        assertEquals(2_000L, RetryPolicy.delayAfter(2))
        assertEquals(4_000L, RetryPolicy.delayAfter(3))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayAfter(50))
    }

    @Test
    fun `Retry-After is honoured but capped`() {
        assertEquals(7_000L, RetryPolicy.delayAfter(1, retryAfterMs = 7_000L))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayAfter(1, retryAfterMs = 3_600_000L))
        assertEquals(0L, RetryPolicy.delayAfter(1, retryAfterMs = 0L))
    }

    @Test
    fun `html bodies are recognised and json is not`() {
        assertTrue(RetryPolicy.looksLikeHtml("<!DOCTYPE html><html><title>x</title></html>"))
        assertTrue(RetryPolicy.looksLikeHtml("  \n<html lang=en>"))
        assertFalse(RetryPolicy.looksLikeHtml("""{"blocked": []}"""))
        assertFalse(RetryPolicy.looksLikeHtml("[]"))
        assertFalse(RetryPolicy.looksLikeHtml(""))
    }
}
