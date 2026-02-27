package ai.rever.bossterm.compose.tui

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import kotlin.test.*

/**
 * Tests for TUI compatibility gaps:
 * - setMouseFormat is wired (no longer a no-op)
 * - DECSET 1004 (focus tracking) is implemented
 *
 * These are essential for TUI apps like vim, tmux, and htop that rely
 * on SGR mouse format and focus events.
 */
class TuiCompatibilityTest {

    private lateinit var display: ComposeTerminalDisplay

    @BeforeTest
    fun setup() {
        display = ComposeTerminalDisplay()
    }

    // ======================== Mouse Format ========================

    @Test
    fun testMouseFormatDefaultIsXterm() {
        assertEquals(
            MouseFormat.MOUSE_FORMAT_XTERM,
            display.mouseFormat.value,
            "Default mouse format should be XTERM"
        )
    }

    @Test
    fun testSetMouseFormatSGR() {
        // SGR format (1006) is used by modern TUI apps for coordinates > 223
        display.setMouseFormat(MouseFormat.MOUSE_FORMAT_SGR)
        assertEquals(
            MouseFormat.MOUSE_FORMAT_SGR,
            display.mouseFormat.value,
            "Mouse format should be updated to SGR"
        )
    }

    @Test
    fun testSetMouseFormatXtermExt() {
        // Xterm extended format (1005) for UTF-8 encoded coordinates
        display.setMouseFormat(MouseFormat.MOUSE_FORMAT_XTERM_EXT)
        assertEquals(
            MouseFormat.MOUSE_FORMAT_XTERM_EXT,
            display.mouseFormat.value,
            "Mouse format should be updated to XTERM_EXT"
        )
    }

    @Test
    fun testSetMouseFormatUrxvt() {
        display.setMouseFormat(MouseFormat.MOUSE_FORMAT_URXVT)
        assertEquals(
            MouseFormat.MOUSE_FORMAT_URXVT,
            display.mouseFormat.value,
            "Mouse format should be updated to URXVT"
        )
    }

    @Test
    fun testMouseFormatResetBackToXterm() {
        display.setMouseFormat(MouseFormat.MOUSE_FORMAT_SGR)
        display.setMouseFormat(MouseFormat.MOUSE_FORMAT_XTERM)
        assertEquals(
            MouseFormat.MOUSE_FORMAT_XTERM,
            display.mouseFormat.value,
            "Mouse format should reset back to XTERM"
        )
    }

    // ======================== Focus Tracking (DECSET 1004) ========================

    @Test
    fun testFocusTrackingDefaultDisabled() {
        assertFalse(
            display.isFocusTrackingEnabled(),
            "Focus tracking should be disabled by default"
        )
    }

    @Test
    fun testFocusTrackingEnable() {
        display.setFocusTracking(true)
        assertTrue(
            display.isFocusTrackingEnabled(),
            "Focus tracking should be enabled after setFocusTracking(true)"
        )
    }

    @Test
    fun testFocusTrackingDisable() {
        display.setFocusTracking(true)
        display.setFocusTracking(false)
        assertFalse(
            display.isFocusTrackingEnabled(),
            "Focus tracking should be disabled after setFocusTracking(false)"
        )
    }

    @Test
    fun testFocusTrackingStateIsObservable() {
        // The focusTrackingEnabled State should be readable from Compose
        val state = display.focusTrackingEnabled
        assertFalse(state.value, "Observable state should reflect initial value")

        display.setFocusTracking(true)
        assertTrue(state.value, "Observable state should update when enabled")
    }

    // ======================== TerminalMode Enum ========================

    @Test
    fun testFocusTrackingModeExists() {
        // Verify the FocusTracking enum value exists (compile-time + runtime)
        val mode = TerminalMode.FocusTracking
        assertEquals("FocusTracking", mode.name)
    }

    @Test
    fun testFocusTrackingModeCallsTerminal() {
        // Verify setEnabled doesn't throw when terminal is null (default no-op path)
        TerminalMode.FocusTracking.setEnabled(null, true)
        TerminalMode.FocusTracking.setEnabled(null, false)
        // No exception means the mode is properly wired
    }
}
