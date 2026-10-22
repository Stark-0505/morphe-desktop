/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SourcesPillTest {

    @Test
    fun `no sources draws no avatars`() {
        assertEquals(0, visibleAvatarCount(total = 0, slots = 10))
    }

    @Test
    fun `sources that fit are all shown with no overflow`() {
        assertEquals(1, visibleAvatarCount(total = 1, slots = 10))
        assertEquals(3, visibleAvatarCount(total = 3, slots = 3))
        assertEquals(5, visibleAvatarCount(total = 5, slots = 10))
    }

    @Test
    fun `overflow reserves one slot for the badge`() {
        assertEquals(3, visibleAvatarCount(total = 5, slots = 4))
        assertEquals(1, visibleAvatarCount(total = 3, slots = 2))
    }

    @Test
    fun `wide pill is capped at five avatars plus badge`() {
        assertEquals(5, visibleAvatarCount(total = 12, slots = 40))
        assertEquals(5, visibleAvatarCount(total = 6, slots = 40))
    }

    @Test
    fun `a badge alone is never drawn`() {
        assertEquals(0, visibleAvatarCount(total = 4, slots = 1))
        assertEquals(0, visibleAvatarCount(total = 4, slots = 0))
    }
}
