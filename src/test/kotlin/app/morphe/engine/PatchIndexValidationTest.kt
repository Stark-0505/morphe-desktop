/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [requirePatchAt]: a mistyped CLI patch index must fail loudly, never silently drop options. */
class PatchIndexValidationTest {

    private val patches = listOf("A", "B", "C")

    @Test
    fun `an in-range index returns that patch`() {
        assertEquals("A", requirePatchAt(patches, 0, "bundle.mpp"))
        assertEquals("C", requirePatchAt(patches, 2, "bundle.mpp"))
    }

    @Test
    fun `an index past the end names the index the bundle and the valid range`() {
        val e = assertFailsWith<IllegalArgumentException> { requirePatchAt(patches, 3, "bundle.mpp") }
        val message = e.message.orEmpty()
        assertTrue("3" in message, message)
        assertTrue("bundle.mpp" in message, message)
        assertTrue("0..2" in message, message)
    }

    @Test
    fun `a negative index is rejected rather than wrapping around`() {
        assertFailsWith<IllegalArgumentException> { requirePatchAt(patches, -1, "bundle.mpp") }
    }

    @Test
    fun `an empty bundle says it has no patches instead of printing an empty range`() {
        val e = assertFailsWith<IllegalArgumentException> { requirePatchAt(emptyList<String>(), 0, "empty.mpp") }
        assertTrue("no patches" in e.message.orEmpty(), e.message)
    }
}
