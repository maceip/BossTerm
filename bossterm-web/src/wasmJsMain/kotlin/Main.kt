import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.CanvasBasedWindow
import ai.rever.bossterm.compose.browser.*
import ai.rever.bossterm.compose.terminal.WebTerminalView

/**
 * BossTerm Web — Compose Multiplatform wasmJs entry point.
 *
 * Renders a terminal UI in the browser using Compose Canvas.
 * Browser APIs (4A-4H) are initialized with feature detection.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Initialize all feature-detected browser API integrations
    BrowserApiIntegrations.initializeAll()

    CanvasBasedWindow(
        canvasElementId = "bosstermCanvas",
        title = "BossTerm"
    ) {
        BossTermWebApp()
    }
}

@Composable
fun BossTermWebApp() {
    var currentView by remember { mutableStateOf("terminal") }
    var showDiagnostics by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF80CBC4),
            onPrimary = Color.Black,
            surface = Color(0xFF1E1E1E),
            onSurface = Color(0xFFE0E0E0),
            background = Color(0xFF121212),
            onBackground = Color(0xFFE0E0E0),
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Status bar with browser API diagnostics
            StatusBar(
                onDiagnosticsClick = { showDiagnostics = !showDiagnostics },
                onViewChange = { currentView = it }
            )

            if (showDiagnostics) {
                BrowserApiDiagnostics()
            }

            // Main content
            when (currentView) {
                "terminal" -> WebTerminalView(
                    modifier = Modifier.fillMaxSize(),
                    columns = 120,
                    rows = 36,
                    fontSize = 14f,
                    fontFamily = FontFamily.Monospace,
                )
                "settings" -> SettingsView(
                    onBack = { currentView = "terminal" }
                )
            }
        }
    }
}

@Composable
fun StatusBar(
    onDiagnosticsClick: () -> Unit,
    onViewChange: (String) -> Unit,
) {
    Surface(
        color = Color(0xFF2D2D2D),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "BossTerm Web",
                color = Color(0xFF80CBC4),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onViewChange("terminal") }) {
                    Text("Terminal", fontSize = 11.sp)
                }
                TextButton(onClick = { onViewChange("settings") }) {
                    Text("Settings", fontSize = 11.sp)
                }
                TextButton(onClick = onDiagnosticsClick) {
                    Text("API Status", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun BrowserApiDiagnostics() {
    Surface(
        color = Color(0xFF1A1A2E),
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Browser API Status",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF80CBC4)
            )
            Spacer(modifier = Modifier.height(8.dp))

            val checks = listOf(
                "SharedArrayBuffer" to DocumentIsolationPolicy.isSharedArrayBufferAvailable(),
                "JSPI" to JspiStatus.isJspiAvailable(),
                "Window Controls Overlay" to WindowControlsOverlay.isSupported(),
                "View Transitions" to ViewTransitions.isSupported(),
                "Navigation API" to NavigationApi.isSupported(),
                "Local Font Access" to LocalFontAccess.isSupported(),
                "EyeDropper" to EyeDropper.isSupported(),
                "Storage Buckets" to StorageBuckets.isSupported(),
                "Storage Bucket Ready" to StorageBuckets.isBucketReady(),
            )

            checks.forEach { (name, supported) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        name,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB0B0B0)
                    )
                    Text(
                        if (supported) "OK" else "--",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (supported) Color(0xFF4CAF50) else Color(0xFF757575)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsView(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("< Back")
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Font picker (uses Local Font Access API)
        var selectedFont by remember { mutableStateOf("Monospace (default)") }

        Text(
            "Terminal Font",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Current: $selectedFont",
            fontSize = 12.sp,
            color = Color(0xFFB0B0B0)
        )
        if (LocalFontAccess.isSupported()) {
            TextButton(onClick = {
                // Font picker would be async — simplified for now
            }) {
                Text("Browse Local Fonts...")
            }
        } else {
            Text(
                "Local Font Access API not available in this browser",
                fontSize = 11.sp,
                color = Color(0xFF757575)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Color picker (uses EyeDropper API)
        Text(
            "Terminal Colors",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (EyeDropper.isSupported()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { /* pick foreground color */ }) {
                    Text("Pick Foreground")
                }
                TextButton(onClick = { /* pick background color */ }) {
                    Text("Pick Background")
                }
                TextButton(onClick = { /* pick cursor color */ }) {
                    Text("Pick Cursor")
                }
            }
        } else {
            Text(
                "EyeDropper API not available in this browser",
                fontSize = 11.sp,
                color = Color(0xFF757575)
            )
        }
    }
}
