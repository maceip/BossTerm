package ai.rever.bossterm.terminal

/**
 * Cursor shape as described by [DECSCUSR](https://vt100.net/docs/vt510-rm/DECSCUSR.html).
 */
enum class CursorShape {
    BLINK_BLOCK,
    STEADY_BLOCK,
    BLINK_UNDERLINE,
    STEADY_UNDERLINE,
    BLINK_VERTICAL_BAR,
    STEADY_VERTICAL_BAR;

    val isBlinking: Boolean
        get() = this == BLINK_BLOCK || this == BLINK_UNDERLINE || this == BLINK_VERTICAL_BAR
}
