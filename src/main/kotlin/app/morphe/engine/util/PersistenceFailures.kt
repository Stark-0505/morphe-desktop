/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Where a failed attempt to save the user's data is announced, so it can't pass unnoticed.
 *
 * A repository that cannot persist a change must not look as if it did. Repositories whose
 * callers can handle an exception throw it; call sites that cannot (fire-and-forget settings
 * changes) report here instead, and the UI shows it once. Reporting also logs, so headless/CLI use
 * still leaves a trace.
 */
object PersistenceFailures {

    /** [subject] is a short human description of what was being saved ("settings", "patch selection"). */
    data class Failure(val subject: String, val cause: Throwable)

    private val _events = MutableSharedFlow<Failure>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits each reported failure. Not replayed: only whoever is observing at the time sees it. */
    val events: SharedFlow<Failure> = _events.asSharedFlow()

    fun report(subject: String, cause: Throwable) {
        Logger.error("Could not save $subject", cause)
        _events.tryEmit(Failure(subject, cause))
    }
}
