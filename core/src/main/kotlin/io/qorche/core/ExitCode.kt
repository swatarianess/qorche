package io.qorche.core

/**
 * Standard CLI exit codes for structured error reporting.
 * CI pipelines can switch on the exit code to handle each case differently.
 */
enum class ExitCode(val code: Int) {
    SUCCESS(0),
    TASK_FAILURE(1),
    CONFIG_ERROR(2),
    CONFLICT(3)
}
