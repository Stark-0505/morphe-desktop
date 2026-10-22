/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Google Play side of icon resolution: which listing URL a package id maps to, where the app
 * icon sits in that listing, and which image hosts we are willing to download it from.
 *
 * Play's markup is not a documented API. Extraction therefore uses two explicit rules and gives
 * up cleanly (`null`) when neither matches, rather than grabbing the first image on the page:
 *  1. the `image` of the page's JSON-LD `SoftwareApplication` block;
 *  2. the `<img>` whose `alt` is "Icon image" (screenshots and ads carry other alt text).
 * Both are checked against [isAllowedImageUrl] before they are returned.
 */
internal object PlayListing {

    private const val LISTING_BASE = "https://play.google.com/store/apps/details"

    /** Android application ids: dot-separated segments, each starting with a letter, at least two. */
    private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private const val MAX_PACKAGE_ID_LENGTH = 255

    /** `play-lh.googleusercontent.com` today; the older `lhN.googleusercontent.com` family is accepted too. */
    private val IMAGE_HOST = Regex("(play-lh|lh[0-9]+)\\.googleusercontent\\.com")

    private val JSON_LD_BLOCK = Regex(
        "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([a-zA-Z-]+)\\s*=\\s*\"([^\"]*)\"|([a-zA-Z-]+)\\s*=\\s*'([^']*)'")

    /** Strings that mean "a challenge or consent wall was served instead of the listing". */
    private val BLOCK_MARKERS = listOf("/recaptcha/", "unusual traffic", "consent.google.com", "captcha-form")

    private val json = Json { isLenient = true }

    fun isValidPackageId(packageId: String): Boolean =
        packageId.length <= MAX_PACKAGE_ID_LENGTH && PACKAGE_ID.matches(packageId)

    /** The public app-detail URL for [packageId], or null when it is not a well-formed id. */
    fun listingUrl(packageId: String): String? {
        if (!isValidPackageId(packageId)) return null
        return "$LISTING_BASE?id=${URLEncoder.encode(packageId, Charsets.UTF_8)}&hl=en"
    }

    /** True for an HTTPS URL on a Google image host, judged on the parsed host, never on substrings. */
    fun isAllowedImageUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return IMAGE_HOST.matches(host)
    }

    /** Whether [html] looks like a bot challenge or consent wall rather than an app listing. */
    fun looksBlocked(html: String): Boolean {
        val lower = html.lowercase()
        return BLOCK_MARKERS.any { it in lower }
    }

    /** The app icon's URL from [html], or null when the listing does not expose one we accept. */
    fun parseIconUrl(html: String): String? =
        jsonLdImage(html)?.takeIf { isAllowedImageUrl(it) }
            ?: iconImageTag(html)?.takeIf { isAllowedImageUrl(it) }

    private fun jsonLdImage(html: String): String? {
        for (block in JSON_LD_BLOCK.findAll(html)) {
            val root = runCatching { json.parseToJsonElement(block.groupValues[1].trim()) }.getOrNull() ?: continue
            val app = softwareApplication(root) ?: continue
            imageValue(app["image"])?.let { return it }
        }
        return null
    }

    private fun softwareApplication(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> element.takeIf { isSoftwareApplication(it["@type"]) }
        is JsonArray -> element.firstNotNullOfOrNull { softwareApplication(it) }
        else -> null
    }

    private fun isSoftwareApplication(type: JsonElement?): Boolean = when (type) {
        is JsonPrimitive -> type.contentOrNull == "SoftwareApplication"
        is JsonArray -> type.any { isSoftwareApplication(it) }
        else -> false
    }

    /** `image` is a string, an `ImageObject` with a `url`, or a list of either. */
    private fun imageValue(image: JsonElement?): String? = when (image) {
        is JsonPrimitive -> image.contentOrNull
        is JsonObject -> imageValue(image["url"])
        is JsonArray -> image.firstNotNullOfOrNull { imageValue(it) }
        else -> null
    }

    private fun iconImageTag(html: String): String? {
        for (tag in IMG_TAG.findAll(html)) {
            val attributes = attributes(tag.value)
            if (!attributes["alt"].equals("Icon image", ignoreCase = true)) continue
            // The largest srcset entry is the sharpest URL the listing itself offers.
            return largestSrcsetEntry(attributes["srcset"]) ?: attributes["src"]
        }
        return null
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTRIBUTE.findAll(tag).associate { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[3] }.lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[4] }
            name to value.replace("&amp;", "&")
        }

    private fun largestSrcsetEntry(srcset: String?): String? =
        srcset?.split(',')
            ?.mapNotNull { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                val url = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val density = parts.getOrNull(1)?.removeSuffix("x")?.toFloatOrNull() ?: 1f
                url to density
            }
            ?.maxByOrNull { it.second }
            ?.first
}
