package io.qorche.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateCommandTest {

    @Test
    fun `formatElapsed shows milliseconds below 1 second`() {
        assertEquals("0ms", formatElapsed(0))
        assertEquals("500ms", formatElapsed(500))
        assertEquals("999ms", formatElapsed(999))
    }

    @Test
    fun `formatElapsed shows seconds at or above 1 second`() {
        assertEquals("1.0s", formatElapsed(1000))
        assertEquals("1.2s", formatElapsed(1234))
        assertEquals("5.7s", formatElapsed(5678))
        assertEquals("60.0s", formatElapsed(60000))
    }

    @Test
    fun `no-color sets Terminal forceColor to false`() {
        val prev = Terminal.forceColor
        try {
            Terminal.forceColor = null
            // Simulate what QorcheCommand.run() does when --no-color is set
            Terminal.forceColor = false
            assertEquals(false, Terminal.forceColor)

            // Verify ANSI codes are stripped
            val output = Terminal.green("test")
            assertEquals("test", output)
        } finally {
            Terminal.forceColor = prev
        }
    }

    @Test
    fun `Terminal produces ANSI when forceColor is true`() {
        val prev = Terminal.forceColor
        try {
            Terminal.forceColor = true
            val output = Terminal.green("test")
            assertTrue(output.contains("\u001b["), "Should contain ANSI escape code")
            assertTrue(output.contains("test"))
        } finally {
            Terminal.forceColor = prev
        }
    }
}
