/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.icon

import app.morphe.engine.util.AtomicFiles
import app.morphe.engine.util.Logger
import kotlinx.serialization.json.Json
import java.io.File

/** Persists and reloads editable [IconProject] state from disk. */
object IconProjectStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        // Use prefixed discriminator to prevent collision with data class properties.
        classDiscriminator = "#kind"
    }

    private fun file(packageName: String) = File(IconExporter.projectDir(packageName), "project.json")

    fun save(project: IconProject, packageName: String) {
        val target = file(packageName)
        // AtomicFiles.write creates the parent directory itself.
        runCatching { AtomicFiles.write(target, json.encodeToString(IconProject.serializer(), project)) }
            .onFailure { Logger.error("IconProjectStore.save failed for $packageName", it) }
    }

    fun load(packageName: String): IconProject? {
        val f = file(packageName)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(IconProject.serializer(), f.readText()) }
            .onFailure { Logger.error("IconProjectStore.load failed for $packageName", it) }
            .getOrNull()
    }
}
