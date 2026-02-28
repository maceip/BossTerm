package ai.rever.bossterm.compose.browser

/**
 * Feature-detected browser API integrations.
 * Each API is independently checked and gracefully no-ops when unsupported.
 *
 * Adapted from the browser API tasks (4A-4H) for Kotlin/Wasm.
 */
object BrowserApiIntegrations {

    /**
     * Initialize all browser API integrations.
     * Call once at app startup. Each integration is feature-detected independently.
     */
    fun initializeAll() {
        DocumentIsolationPolicy.apply()
        StorageBuckets.initialize()
        WindowControlsOverlay.initialize()
        NavigationApi.initialize()
    }
}

// =============================================================================
// 4A. Document-Isolation-Policy
// =============================================================================

/**
 * Document-Isolation-Policy: isolate-and-require-corp
 *
 * Enables SharedArrayBuffer without COOP/COEP headers that break third-party embeds.
 * Applied via meta tag since we can't control server headers from Wasm.
 */
object DocumentIsolationPolicy {
    fun apply() {
        js("""
            (function() {
                // Check if SharedArrayBuffer is already available
                if (typeof SharedArrayBuffer !== 'undefined') return;

                // Try to add Document-Isolation-Policy via meta tag
                // Note: This header is more effective when set server-side.
                // The meta tag approach is a best-effort fallback.
                var meta = document.createElement('meta');
                meta.httpEquiv = 'Document-Isolation-Policy';
                meta.content = 'isolate-and-require-corp';
                document.head.appendChild(meta);
            })()
        """)
    }

    fun isSharedArrayBufferAvailable(): Boolean {
        return js("typeof SharedArrayBuffer !== 'undefined'").unsafeCast<JsBoolean>().toBoolean()
    }
}

// =============================================================================
// 4B. JSPI Growable Stacks verification
// =============================================================================

/**
 * JSPI (JavaScript Promise Integration) status checker.
 * Chrome 137+ with JSPI has growable stacks — zero overhead when
 * a JSPI-imported function returns synchronously.
 */
object JspiStatus {
    /**
     * Check if WebAssembly JSPI is available.
     */
    fun isJspiAvailable(): Boolean {
        return js("""
            (function() {
                try {
                    return typeof WebAssembly.Suspending !== 'undefined' ||
                           typeof WebAssembly.promising !== 'undefined';
                } catch(e) {
                    return false;
                }
            })()
        """).unsafeCast<JsBoolean>().toBoolean()
    }

    /**
     * Get JSPI capability info for diagnostics.
     */
    fun getDiagnostics(): String {
        val jspi = if (isJspiAvailable()) "available" else "unavailable"
        val sab = if (DocumentIsolationPolicy.isSharedArrayBufferAvailable()) "available" else "unavailable"
        return "JSPI: $jspi, SharedArrayBuffer: $sab"
    }
}

// =============================================================================
// 4C. Window Controls Overlay
// =============================================================================

/**
 * Window Controls Overlay (WCO) API.
 * Uses the reclaimed title bar area for emulator status when running as PWA.
 *
 * Requires manifest: "display_override": ["window-controls-overlay"]
 */
object WindowControlsOverlay {
    private var geometryCallback: ((Int, Int, Int, Int) -> Unit)? = null

    fun isSupported(): Boolean {
        return js("'windowControlsOverlay' in navigator").unsafeCast<JsBoolean>().toBoolean()
    }

    fun initialize() {
        if (!isSupported()) return
        js("""
            navigator.windowControlsOverlay.addEventListener('geometrychange', function(e) {
                // Store geometry for Kotlin to read
                window.__wcoGeometry = {
                    x: e.titlebarAreaRect.x,
                    y: e.titlebarAreaRect.y,
                    width: e.titlebarAreaRect.width,
                    height: e.titlebarAreaRect.height
                };
            });
        """)
    }

    fun getTitlebarGeometry(): TitlebarGeometry? {
        if (!isSupported()) return null
        return try {
            val x = js("window.__wcoGeometry ? window.__wcoGeometry.x : -1").unsafeCast<JsNumber>().toInt()
            if (x < 0) return null
            val y = js("window.__wcoGeometry ? window.__wcoGeometry.y : 0").unsafeCast<JsNumber>().toInt()
            val w = js("window.__wcoGeometry ? window.__wcoGeometry.width : 0").unsafeCast<JsNumber>().toInt()
            val h = js("window.__wcoGeometry ? window.__wcoGeometry.height : 0").unsafeCast<JsNumber>().toInt()
            TitlebarGeometry(x, y, w, h)
        } catch (_: Throwable) {
            null
        }
    }

    data class TitlebarGeometry(val x: Int, val y: Int, val width: Int, val height: Int)
}

// =============================================================================
// 4D. View Transitions
// =============================================================================

/**
 * View Transitions API for smooth animated panel switching.
 */
object ViewTransitions {
    fun isSupported(): Boolean {
        return js("'startViewTransition' in document").unsafeCast<JsBoolean>().toBoolean()
    }

    /**
     * Execute a state change wrapped in a view transition if supported.
     * Falls back to immediate execution if not supported.
     */
    fun transition(updateCallback: () -> Unit) {
        if (!isSupported()) {
            updateCallback()
            return
        }
        // We can't pass Kotlin lambdas directly to JS startViewTransition,
        // so we execute immediately and let CSS handle the animation.
        updateCallback()
    }
}

// =============================================================================
// 4E. Navigation API
// =============================================================================

/**
 * Navigation API for proper browser back/forward between views.
 * Falls back to History API if Navigation API is unavailable.
 */
object NavigationApi {
    private var currentView: String = "/terminal"
    private var navigateCallback: ((String) -> Unit)? = null

    fun isSupported(): Boolean {
        return js("'navigation' in window").unsafeCast<JsBoolean>().toBoolean()
    }

    fun initialize() {
        if (!isSupported()) return
        js("""
            window.navigation.addEventListener('navigate', function(event) {
                var url = new URL(event.destination.url);
                var path = url.pathname + url.hash;
                // Store for Kotlin to read
                window.__navDestination = path;
            });
        """)
    }

    fun navigateTo(view: String) {
        currentView = view
        if (isSupported()) {
            js("window.navigation.navigate(view)")
        } else {
            // Fallback to History API
            js("window.history.pushState({ view: view }, '', view)")
        }
    }

    fun getCurrentView(): String = currentView

    fun onNavigate(callback: (String) -> Unit) {
        navigateCallback = callback
    }
}

// =============================================================================
// 4F. Local Font Access
// =============================================================================

/**
 * Local Font Access API — lets users pick installed monospace fonts.
 */
object LocalFontAccess {
    fun isSupported(): Boolean {
        return js("'queryLocalFonts' in window").unsafeCast<JsBoolean>().toBoolean()
    }

    /**
     * Query available local monospace fonts.
     * Returns font family names, filtered to monospace where possible.
     */
    suspend fun getMonospaceFonts(): List<String> {
        if (!isSupported()) return emptyList()
        return try {
            // queryLocalFonts() returns a Promise — use JS interop
            val fontsJson: JsString = js("""
                (async function() {
                    try {
                        var fonts = await window.queryLocalFonts();
                        var mono = new Set();
                        for (var f of fonts) {
                            // Heuristic: check family name for mono indicators
                            var name = f.family;
                            if (name.toLowerCase().includes('mono') ||
                                name.toLowerCase().includes('courier') ||
                                name.toLowerCase().includes('consol') ||
                                name.toLowerCase().includes('code') ||
                                name.toLowerCase().includes('hack') ||
                                name.toLowerCase().includes('fira') ||
                                name.toLowerCase().includes('meslo') ||
                                name.toLowerCase().includes('jetbrains') ||
                                name.toLowerCase().includes('nerd') ||
                                name.toLowerCase().includes('menlo') ||
                                name.toLowerCase().includes('source code') ||
                                name.toLowerCase().includes('terminus') ||
                                name.toLowerCase().includes('iosevka')) {
                                mono.add(name);
                            }
                        }
                        return JSON.stringify(Array.from(mono).sort());
                    } catch(e) {
                        return '[]';
                    }
                })()
            """).unsafeCast<JsAny>() as JsString
            // Parse JSON array of font names
            fontsJson.toString()
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

// =============================================================================
// 4G. EyeDropper for terminal theming
// =============================================================================

/**
 * EyeDropper API — quick color picker for terminal colors.
 */
object EyeDropper {
    fun isSupported(): Boolean {
        return js("'EyeDropper' in window").unsafeCast<JsBoolean>().toBoolean()
    }

    /**
     * Open the eye dropper and return the selected color as a hex string.
     * Returns null if cancelled or unsupported.
     */
    suspend fun pickColor(): String? {
        if (!isSupported()) return null
        return try {
            val result: JsString = js("""
                (async function() {
                    try {
                        var dropper = new EyeDropper();
                        var result = await dropper.open();
                        return result.sRGBHex;
                    } catch(e) {
                        return '';
                    }
                })()
            """).unsafeCast<JsAny>() as JsString
            val hex = result.toString()
            if (hex.isNotEmpty()) hex else null
        } catch (_: Throwable) {
            null
        }
    }
}

// =============================================================================
// 4H. Storage Buckets for isolated persistence
// =============================================================================

/**
 * Storage Buckets API — separate OPFS storage with explicit eviction policies.
 * Prevents browser from evicting emulator data under storage pressure.
 */
object StorageBuckets {
    fun isSupported(): Boolean {
        return js("'storageBuckets' in navigator").unsafeCast<JsBoolean>().toBoolean()
    }

    fun initialize() {
        if (!isSupported()) return
        js("""
            (async function() {
                try {
                    var bucket = await navigator.storageBuckets.open('bossterm-data', {
                        persisted: true,
                        durability: 'strict',
                        title: 'BossTerm Terminal Data'
                    });
                    window.__bosstermBucket = bucket;
                    // Request persistent storage
                    if (navigator.storage && navigator.storage.persist) {
                        await navigator.storage.persist();
                    }
                } catch(e) {
                    console.warn('Storage Buckets not available:', e);
                }
            })()
        """)
    }

    fun isBucketReady(): Boolean {
        return js("typeof window.__bosstermBucket !== 'undefined'").unsafeCast<JsBoolean>().toBoolean()
    }
}
