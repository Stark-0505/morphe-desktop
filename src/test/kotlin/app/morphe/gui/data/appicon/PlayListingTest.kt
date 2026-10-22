/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTML below is hand-written FIXTURE markup shaped like the two places a Play listing exposes
 * its icon. It is not a capture of a live response, and these tests say nothing about whether
 * Google's current markup still matches; they pin down our extraction and validation rules.
 */
class PlayListingTest {

    private val icon = "https://play-lh.googleusercontent.com/abc123-icon=s256-rw"
    private val screenshot = "https://play-lh.googleusercontent.com/zzz-screenshot=w526-h296-rw"

    private fun jsonLd(image: String, type: String = "SoftwareApplication") =
        """<script type="application/ld+json">{"@context":"https://schema.org","@type":"$type","name":"App","image":"$image"}</script>"""

    private fun iconImg(src: String, srcset: String? = null, alt: String = "Icon image") =
        """<img src="$src" ${srcset?.let { "srcset=\"$it\"" } ?: ""} alt="$alt" itemprop="image">"""

    @Test
    fun `a valid package id maps to the public app-detail url`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.google.android.youtube&hl=en",
            PlayListing.listingUrl("com.google.android.youtube"),
        )
    }

    @Test
    fun `malformed package ids produce no url`() {
        listOf("", "nodots", ".leading.dot", "trailing.dot.", "has space.app", "a.b&evil=1", "com.1abc", "x/y.z", "a".repeat(300) + ".b")
            .forEach { assertNull(PlayListing.listingUrl(it), "'$it' must be rejected") }
    }

    @Test
    fun `the icon comes from the JSON-LD SoftwareApplication block`() {
        val html = "<html>${jsonLd(icon)}${iconImg(screenshot, alt = "Screenshot image")}</html>"
        assertEquals(icon, PlayListing.parseIconUrl(html))
    }

    @Test
    fun `JSON-LD image may be an object or a list`() {
        val asObject = """<script type="application/ld+json">{"@type":"SoftwareApplication","image":{"url":"$icon"}}</script>"""
        val asList = """<script type="application/ld+json">[{"@type":"SoftwareApplication","image":["$icon"]}]</script>"""
        assertEquals(icon, PlayListing.parseIconUrl(asObject))
        assertEquals(icon, PlayListing.parseIconUrl(asList))
    }

    @Test
    fun `JSON-LD blocks of other types are ignored`() {
        assertNull(PlayListing.parseIconUrl(jsonLd(icon, type = "Organization")))
    }

    @Test
    fun `without JSON-LD the img with the Icon image alt text is used, largest srcset entry first`() {
        val small = "https://play-lh.googleusercontent.com/abc123-icon=s64-rw"
        val html = "<img src=\"$screenshot\" alt=\"Screenshot image\">" + iconImg(small, "$small 1x, $icon 2x")
        assertEquals(icon, PlayListing.parseIconUrl(html))
    }

    @Test
    fun `attribute entities are decoded`() {
        val html = iconImg("https://play-lh.googleusercontent.com/abc=s64-rw?x=1&amp;y=2")
        assertEquals("https://play-lh.googleusercontent.com/abc=s64-rw?x=1&y=2", PlayListing.parseIconUrl(html))
    }

    @Test
    fun `screenshots and unrelated images are never taken as the icon`() {
        val html = iconImg(screenshot, alt = "Screenshot image") +
            """<img src="https://play-lh.googleusercontent.com/ad" alt="Advertisement">"""
        assertNull(PlayListing.parseIconUrl(html))
    }

    @Test
    fun `missing or changed metadata and malformed pages yield null`() {
        assertNull(PlayListing.parseIconUrl(""))
        assertNull(PlayListing.parseIconUrl("<html><body>nothing here</body></html>"))
        assertNull(PlayListing.parseIconUrl("""<script type="application/ld+json">{not json</script>"""))
        assertNull(PlayListing.parseIconUrl("<img alt=\"Icon image\""))
    }

    @Test
    fun `a disallowed JSON-LD host falls through to the next rule instead of being trusted`() {
        val html = jsonLd("https://evil.example/icon.png") + iconImg(icon)
        assertEquals(icon, PlayListing.parseIconUrl(html))
    }

    @Test
    fun `only https urls on Google image hosts are accepted`() {
        listOf(
            "https://play-lh.googleusercontent.com/x=s64",
            "https://lh3.googleusercontent.com/x=s64",
            "https://PLAY-LH.googleusercontent.com:443/x",
        ).forEach { assertTrue(PlayListing.isAllowedImageUrl(it), it) }

        listOf(
            "http://play-lh.googleusercontent.com/x",
            "https://evil.example/play-lh.googleusercontent.com/x",
            "https://play-lh.googleusercontent.com.evil.example/x",
            "https://evilgoogleusercontent.com/x",
            "https://googleusercontent.com/x",
            "https://user@play-lh.googleusercontent.com/x",
            "https://play-lh.googleusercontent.com:8443/x",
            "ftp://play-lh.googleusercontent.com/x",
            "//play-lh.googleusercontent.com/x",
            "not a url",
            "",
        ).forEach { assertFalse(PlayListing.isAllowedImageUrl(it), it) }
    }

    @Test
    fun `challenge and consent pages are recognized`() {
        assertTrue(PlayListing.looksBlocked("<form id=\"captcha-form\" action=\"/recaptcha/api\">"))
        assertTrue(PlayListing.looksBlocked("Our systems have detected unusual traffic"))
        assertTrue(PlayListing.looksBlocked("<a href=\"https://consent.google.com/m?continue=\">"))
        assertFalse(PlayListing.looksBlocked("<html>${jsonLd(icon)}</html>"))
    }
}
