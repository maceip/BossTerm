package ai.rever.bossterm.compose.update

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Configuration for GitHub API access.
 *
 * GitHub API rate limits:
 * - Unauthenticated: 60 requests/hour
 * - Authenticated: 5,000 requests/hour
 *
 * The GitHub token is obtained from multiple sources (in order):
 * 1. Environment variable: GITHUB_TOKEN
 * 2. System property: GITHUB_TOKEN
 * 3. GitHub CLI (gh auth token) — with timeout to prevent blocking
 * 4. No token (fallback to unauthenticated access)
 *
 * Note: local.properties is intentionally NOT read because it stores tokens
 * in plaintext on disk, which is a security risk for credential leakage.
 * Prefer environment variables or the GitHub CLI credential store instead.
 */
object GitHubConfig {
    /** Timeout for subprocess calls (e.g., gh auth token). */
    private const val SUBPROCESS_TIMEOUT_SECONDS = 5L

    /**
     * GitHub Personal Access Token loaded from secure sources.
     * Attempts to use GitHub CLI if no token is explicitly configured.
     * Returns null if not configured (will use unauthenticated access).
     */
    val token: String? by lazy {
        getConfiguredToken() ?: getTokenFromGitHubCLI()
    }

    /**
     * Check if GitHub token is configured
     */
    val hasToken: Boolean
        get() = token != null

    /**
     * Get token from environment or system property.
     */
    private fun getConfiguredToken(): String? {
        // 1. Environment variable (secure: set by user's shell profile or CI)
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. System property (secure: set by JVM launch flags)
        System.getProperty("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    /**
     * Attempt to retrieve token from GitHub CLI (gh auth token).
     * Returns null if gh is not installed, not authenticated, or times out.
     *
     * Uses a bounded timeout to prevent blocking the application startup
     * if the gh process hangs (e.g., waiting for keyring unlock).
     */
    private fun getTokenFromGitHubCLI(): String? {
        return try {
            val process = ProcessBuilder("gh", "auth", "token")
                .redirectErrorStream(true)
                .start()

            // Read with a timeout to prevent indefinite blocking
            val completed = process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }

            if (process.exitValue() != 0) return null

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }

            if (output.isNotBlank() && !output.contains("not logged in", ignoreCase = true)) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            // gh command not found or other error - silently ignore
            null
        }
    }
}
