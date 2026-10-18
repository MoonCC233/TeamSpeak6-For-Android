package dev.tsdroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server info sheet renders whatever the server sends. These two helpers are
 * the only transformation applied to it, so they are pinned here.
 */
class ServerInfoSheetTest {

    @Test
    fun `bbcode tags are removed but text is kept`() {
        assertEquals(
            "Welcome to our server!",
            stripBbCode("[b]Welcome[/b] to [color=red]our[/color] server!"),
        )
    }

    @Test
    fun `bbcode list and url tags do not leak into the output`() {
        assertEquals(
            "Rules: be nice",
            stripBbCode("[size=12][list][*]Rules: be nice[/list][/size]"),
        )
    }

    @Test
    fun `line breaks survive and surrounding whitespace is trimmed`() {
        assertEquals("line one\nline two", stripBbCode("\n[b]line one[/b]\nline two\n"))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("just text", stripBbCode("just text"))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", stripBbCode(""))
        assertEquals("", stripBbCode("   "))
    }

    @Test
    fun `uptime uses the two most significant units`() {
        assertEquals("2d 5h", formatUptime(2 * 86_400L + 5 * 3_600L + 42))
        assertEquals("5h 3m", formatUptime(5 * 3_600L + 3 * 60L + 9))
        assertEquals("3m 9s", formatUptime(3 * 60L + 9))
        assertEquals("42s", formatUptime(42))
    }

    @Test
    fun `unknown uptime renders as a dash rather than zero`() {
        assertEquals("—", formatUptime(0))
        assertEquals("—", formatUptime(-1))
    }
}
