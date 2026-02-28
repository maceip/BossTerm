package ai.rever.bossterm.compose.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TextStyle as TermTextStyle

/**
 * Web terminal view using Compose Canvas rendering.
 * This is the wasmJs equivalent of the desktop ProperTerminal.
 *
 * Currently renders a terminal grid with cursor support.
 * Full emulation requires the core engine migration to commonMain.
 */
@Composable
fun WebTerminalView(
    modifier: Modifier = Modifier,
    columns: Int = 80,
    rows: Int = 24,
    fontSize: Float = 14f,
    fontFamily: FontFamily = FontFamily.Monospace,
) {
    val focusRequester = remember { FocusRequester() }
    var cursorX by remember { mutableStateOf(0) }
    var cursorY by remember { mutableStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }
    var cursorShape by remember { mutableStateOf(CursorShape.BLINK_BLOCK) }

    // Terminal buffer: rows x columns of characters
    val buffer = remember { Array(rows) { CharArray(columns) { ' ' } } }
    // Style buffer for each cell
    val fgColors = remember { Array(rows) { Array(columns) { Color.White } } }
    val bgColors = remember { Array(rows) { Array(columns) { Color.Black } } }

    // Input buffer for characters typed
    var inputBuffer by remember { mutableStateOf("") }

    // Cell dimensions derived from font size
    val cellWidth = fontSize * 0.6f
    val cellHeight = fontSize * 1.2f

    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        // Write welcome message
        val welcome = "BossTerm Web Terminal [Compose/WasmJs]"
        val prompt = "$ "
        welcome.forEachIndexed { i, c ->
            if (i < columns) {
                buffer[0][i] = c
                fgColors[0][i] = Color.Green
            }
        }
        prompt.forEachIndexed { i, c ->
            if (i < columns) {
                buffer[2][i] = c
                fgColors[2][i] = Color.Cyan
            }
        }
        cursorX = prompt.length
        cursorY = 2
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        // Enter key
                        event.key == Key.Enter -> {
                            if (cursorY < rows - 1) {
                                cursorY++
                                cursorX = 0
                                // Write new prompt
                                val p = "$ "
                                p.forEachIndexed { i, c ->
                                    if (i < columns) {
                                        buffer[cursorY][i] = c
                                        fgColors[cursorY][i] = Color.Cyan
                                    }
                                }
                                cursorX = p.length
                            }
                            true
                        }
                        // Backspace
                        event.key == Key.Backspace -> {
                            if (cursorX > 2) { // Don't delete past prompt
                                cursorX--
                                buffer[cursorY][cursorX] = ' '
                            }
                            true
                        }
                        // Printable character
                        event.utf16CodePoint in 0x20..0x7E -> {
                            if (cursorX < columns) {
                                val c = event.utf16CodePoint.toChar()
                                buffer[cursorY][cursorX] = c
                                fgColors[cursorY][cursorX] = Color.White
                                cursorX++
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Draw background
            drawRect(Color.Black, Offset.Zero, Size(canvasWidth, canvasHeight))

            // Draw each cell
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    val x = col * cellWidth
                    val y = row * cellHeight

                    // Draw cell background if not black
                    val bg = bgColors[row][col]
                    if (bg != Color.Black) {
                        drawRect(bg, Offset(x, y), Size(cellWidth, cellHeight))
                    }
                }
            }

            // Draw cursor
            if (cursorVisible) {
                val cx = cursorX * cellWidth
                val cy = cursorY * cellHeight
                when (cursorShape) {
                    CursorShape.BLINK_BLOCK, CursorShape.STEADY_BLOCK -> {
                        drawRect(
                            Color.White.copy(alpha = 0.5f),
                            Offset(cx, cy),
                            Size(cellWidth, cellHeight)
                        )
                    }
                    CursorShape.BLINK_UNDERLINE, CursorShape.STEADY_UNDERLINE -> {
                        drawRect(
                            Color.White,
                            Offset(cx, cy + cellHeight - 2),
                            Size(cellWidth, 2f)
                        )
                    }
                    CursorShape.BLINK_VERTICAL_BAR, CursorShape.STEADY_VERTICAL_BAR -> {
                        drawRect(
                            Color.White,
                            Offset(cx, cy),
                            Size(2f, cellHeight)
                        )
                    }
                }
            }
        }

        // Render text on top of canvas
        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until rows) {
                Row(modifier = Modifier.height(cellHeight.dp / 1.5f)) {
                    for (col in 0 until columns) {
                        val ch = buffer[row][col]
                        if (ch != ' ' && ch != '\u0000') {
                            Text(
                                text = ch.toString(),
                                color = fgColors[row][col],
                                fontSize = fontSize.sp,
                                fontFamily = fontFamily,
                                modifier = Modifier.width(cellWidth.dp / 1.5f)
                            )
                        } else {
                            Spacer(modifier = Modifier.width(cellWidth.dp / 1.5f))
                        }
                    }
                }
            }
        }
    }
}
