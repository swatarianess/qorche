package io.qorche.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitCodeTest {

    @Test
    fun `SUCCESS is 0`() {
        assertEquals(0, ExitCode.SUCCESS.code)
    }

    @Test
    fun `TASK_FAILURE is 1`() {
        assertEquals(1, ExitCode.TASK_FAILURE.code)
    }

    @Test
    fun `CONFIG_ERROR is 2`() {
        assertEquals(2, ExitCode.CONFIG_ERROR.code)
    }

    @Test
    fun `CONFLICT is 3`() {
        assertEquals(3, ExitCode.CONFLICT.code)
    }

    @Test
    fun `all exit codes have unique integer values`() {
        val codes = ExitCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
