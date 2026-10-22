/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.appicon

import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Why a package has no icon right now; decides how long [AppIconCache] suppresses a retry. */
@Serializable
internal enum class IconFailure(val baseCooldownMs: Long, val maxCooldownMs: Long) {
    /** Play has no listing for the package (404/410, or any other definitive 4xx). Stable. */
    NO_LISTING(7 * DAY, 7 * DAY),

    /** A listing or image arrived but holds no usable icon: changed markup, bad image. */
    NO_ICON(1 * DAY, 1 * DAY),

    /** A 403, challenge or consent page. Not retried quickly; we do not work around it. */
    BLOCKED(6 * HOUR, 24 * HOUR),

    /** Offline, timeout, 5xx or 429: worth trying again soon, with growing gaps. */
    TRANSIENT(15 * MINUTE, 12 * HOUR),
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * On-disk home of resolved app icons, in one directory:
 *  - `<sha256(packageId)>.img`: the icon exactly as Google served it;
 *  - `failures.json`: the cooldown index for packages that could not be resolved.
 *
 * A successful icon needs no metadata: its file existing and decoding is the whole record. Only
 * failures are indexed, so a package Play cannot serve is not asked about on every launch.
 *
 * Package ids are never used as paths, only their digest. Every write goes through a temp file
 * and an atomic move, so a crash or failed write leaves the previous valid state in place.
 */
internal class AppIconCache(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    private data class Entry(val kind: IconFailure, val atMs: Long, val attempts: Int, val retryAfterMs: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val indexFile get() = File(dir, "failures.json")
    private val lock = Any()
    private var failures: MutableMap<String, Entry>? = null

    fun iconFile(packageId: String): File = File(dir, "${digest(packageId)}.img")

    /** Scratch path for a download that has not been validated yet. */
    fun downloadFile(packageId: String): File = File(dir, "${digest(packageId)}.dl")

    fun listingFile(packageId: String): File = File(dir, "${digest(packageId)}.html")

    fun readIcon(packageId: String): ByteArray? =
        runCatching { iconFile(packageId).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** Moves a validated download into place as the icon; an existing icon is replaced atomically. */
    @Throws(IOException::class)
    fun commit(packageId: String, validated: File) {
        AtomicFiles.replaceFile(validated, iconFile(packageId))
        clearFailure(packageId)
    }

    fun deleteIcon(packageId: String) {
        runCatching { iconFile(packageId).delete() }
    }

    /** The failure still inside its cooldown, or null when the package may be tried again. */
    fun activeFailure(packageId: String): IconFailure? = synchronized(lock) {
        val entry = loadFailures()[packageId] ?: return null
        val now = clock()
        // A clock that moved backwards must not lock a package out indefinitely.
        if (now < entry.atMs) return null
        val cooldown = maxOf(cooldownMs(entry.kind, entry.attempts), entry.retryAfterMs)
        entry.kind.takeIf { now < entry.atMs + cooldown }
    }

    fun recordFailure(packageId: String, kind: IconFailure, retryAfterMs: Long? = null) = synchronized(lock) {
        val map = loadFailures()
        val attempts = (map[packageId]?.takeIf { it.kind == kind }?.attempts ?: 0) + 1
        map[packageId] = Entry(kind, clock(), attempts, (retryAfterMs ?: 0).coerceIn(0, DAY))
        persist(map)
    }

    fun clearFailure(packageId: String) = synchronized(lock) {
        val map = loadFailures()
        if (map.remove(packageId) != null) persist(map)
    }

    /** Forgets the in-memory index so the next read reloads it, e.g. after the directory was cleared. */
    fun reset() = synchronized(lock) { failures = null }

    private fun cooldownMs(kind: IconFailure, attempts: Int): Long {
        var cooldown = kind.baseCooldownMs
        repeat((attempts - 1).coerceIn(0, 16)) { cooldown = (cooldown * 2).coerceAtMost(kind.maxCooldownMs) }
        return cooldown.coerceAtMost(kind.maxCooldownMs)
    }

    private fun loadFailures(): MutableMap<String, Entry> {
        failures?.let { return it }
        val loaded = try {
            indexFile.takeIf { it.isFile }
                ?.let { json.decodeFromString(serializer, it.readText()).toMutableMap() }
                ?: mutableMapOf()
        } catch (e: Exception) {
            // A corrupt index costs at most one extra lookup per package; it is not worth failing over.
            Logger.warn("Ignoring unreadable app-icon failure index: ${e.message}")
            mutableMapOf()
        }
        failures = loaded
        return loaded
    }

    private fun persist(map: Map<String, Entry>) {
        try {
            AtomicFiles.write(indexFile, json.encodeToString(serializer, map))
        } catch (e: IOException) {
            Logger.warn("Could not persist the app-icon failure index: ${e.message}")
        }
    }

    private fun digest(packageId: String): String =
        MessageDigest.getInstance("SHA-256").digest(packageId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
