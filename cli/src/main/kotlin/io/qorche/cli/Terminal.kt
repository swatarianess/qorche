package io.qorche.cli

object Terminal {
    var forceColor: Boolean? = null

    private val colorEnabled: Boolean by lazy {
        forceColor ?: (
            System.getenv("NO_COLOR") == null
            && System.getenv("TERM") != "dumb"
            && (System.console() != null
                || System.getenv("TERM") != null
                || System.getenv("WT_SESSION") != null
                || System.getenv("COLORTERM") != null)
        )
    }

    private fun ansi(code: String, text: String): String =
        if (forceColor ?: colorEnabled) "\u001b[${code}m$text\u001b[0m" else text

    fun green(text: String) = ansi("32", text)
    fun red(text: String) = ansi("31", text)
    fun yellow(text: String) = ansi("33", text)
    fun cyan(text: String) = ansi("36", text)
    fun dim(text: String) = ansi("2", text)
    fun bold(text: String) = ansi("1", text)
}
