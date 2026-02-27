package ai.rever.bossterm.compose.util

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI
import javax.swing.JOptionPane
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-platform utility for opening URLs in the default browser.
 * Consolidates URL opening logic with proper fallbacks for all platforms.
 */
object UrlOpener {
    private val blockedOpenCount = AtomicLong(0)

    data class UrlOpenPolicy(
        val allowedSchemes: Set<String> = DEFAULT_ALLOWED_SCHEMES,
        val allowFileUrls: Boolean = true,
        val promptBeforeFileUrlOpen: Boolean = true
    )

    @Volatile
    private var openPolicy = UrlOpenPolicy()

    private val commandAvailabilityCache = mutableMapOf<String, Pair<Long, Boolean>>()
    private const val COMMAND_CACHE_TTL_MS = 60_000L

    private val cacheLock = Any()

    private val defaultBrowserCommands = listOf(
        "xdg-open",        // Standard Linux
        "sensible-browser", // Debian/Ubuntu
        "x-www-browser",   // Debian alternatives
        "gnome-open",      // GNOME
        "kde-open",        // KDE
        "firefox",         // Direct browser fallbacks
        "google-chrome",
        "chromium",
        "chromium-browser"
    )

    private val urlOpenCommandArgs = mapOf(
        "xdg-open" to listOf("xdg-open"),
        "sensible-browser" to listOf("sensible-browser"),
        "x-www-browser" to listOf("x-www-browser"),
        "gnome-open" to listOf("gnome-open"),
        "kde-open" to listOf("kde-open"),
        "firefox" to listOf("firefox"),
        "google-chrome" to listOf("google-chrome"),
        "chromium" to listOf("chromium"),
        "chromium-browser" to listOf("chromium-browser")
    )

    private val windowsStartCommand = listOf("cmd", "/c", "start", "")

    /**
     * Open a URL in the default browser.
     *
     * @param url The URL to open
     * @return true if the URL was successfully opened, false otherwise
     */
    fun open(url: String): Boolean {
        val normalizedUri = normalizeAndValidate(url) ?: return false
        if (!confirmFileUrlOpenIfRequired(normalizedUri)) {
            blockedOpenCount.incrementAndGet()
            return false
        }

        return try {
            when {
                ShellCustomizationUtils.isLinux() -> openOnLinux(normalizedUri)
                ShellCustomizationUtils.isMacOS() -> {
                    ProcessBuilder("open", normalizedUri.toString()).start()
                    true
                }
                ShellCustomizationUtils.isWindows() -> {
                    ProcessBuilder(windowsStartCommand + normalizedUri.toString()).start()
                    true
                }
                else -> openWithDesktop(normalizedUri)
            }
        } catch (e: Exception) {
            println("Failed to open URL: ${normalizedUri} - ${e.message}")
            false
        }
    }

    fun setPolicy(policy: UrlOpenPolicy) {
        openPolicy = policy
    }

    fun blockedRequestCount(): Long = blockedOpenCount.get()

    private fun normalizeAndValidate(url: String): URI? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            blockedOpenCount.incrementAndGet()
            return null
        }

        val uri = try {
            URI(trimmed).normalize()
        } catch (e: Exception) {
            println("Blocked malformed URL: $trimmed (${e.message})")
            blockedOpenCount.incrementAndGet()
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme.isNullOrBlank()) {
            println("Blocked URL without scheme: $trimmed")
            blockedOpenCount.incrementAndGet()
            return null
        }

        val policy = openPolicy
        val allowedByScheme = scheme in policy.allowedSchemes
        val allowedFileUrl = scheme != "file" || policy.allowFileUrls
        if (!allowedByScheme || !allowedFileUrl) {
            println("Blocked URL scheme '$scheme': $trimmed")
            blockedOpenCount.incrementAndGet()
            return null
        }

        return uri
    }

    private fun confirmFileUrlOpenIfRequired(uri: URI): Boolean {
        val policy = openPolicy
        if (!uri.scheme.equals("file", ignoreCase = true) || !policy.promptBeforeFileUrlOpen) {
            return true
        }

        if (GraphicsEnvironment.isHeadless()) {
            println("Blocked file URL in headless mode: $uri")
            return false
        }

        val answer = JOptionPane.showConfirmDialog(
            null,
            "Open local file?\n$uri",
            "Confirm Local File Access",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return answer == JOptionPane.OK_OPTION
    }

    /**
     * Open URL on Linux using multiple fallback strategies.
     * Tries xdg-open first (most standard), then various browser alternatives.
     */
    private fun openOnLinux(uri: URI): Boolean {
        for (command in defaultBrowserCommands) {
            try {
                if (isCommandAvailable(command)) {
                    val baseArgs = urlOpenCommandArgs[command] ?: continue
                    ProcessBuilder(baseArgs + uri.toString())
                        .redirectErrorStream(true)
                        .start()
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }

        // Final fallback: try Java Desktop API
        return openWithDesktop(uri)
    }

    /**
     * Try to open URL using Java's Desktop API.
     */
    private fun openWithDesktop(uri: URI): Boolean {
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isCommandAvailable(command: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cached = commandAvailabilityCache[command]
            if (cached != null && now - cached.first <= COMMAND_CACHE_TTL_MS) {
                return cached.second
            }
        }

        val exists = try {
            val whichProcess = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            val completed = whichProcess.waitFor(2, TimeUnit.SECONDS)
            completed && whichProcess.exitValue() == 0
        } catch (_: Exception) {
            false
        }

        synchronized(cacheLock) {
            commandAvailabilityCache[command] = now to exists
        }
        return exists
    }

    private val DEFAULT_ALLOWED_SCHEMES = setOf(
        "http",
        "https",
        "mailto",
        "file",
        "ssh"
    )
}
