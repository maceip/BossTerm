package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.security.CredentialProviders
import ai.rever.bossterm.compose.util.SubprocessHelper

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
 * 3. OS credential provider (service=bossterm.github, account=api_token)
 * 4. GitHub CLI (gh auth token)
 * 5. No token (fallback to unauthenticated access)
 */
object GitHubConfig {
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

        // 3. OS credential provider abstraction
        CredentialProviders.default.read(service = "bossterm.github", account = "api_token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
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
            val result = SubprocessHelper.run("gh", "auth", "token", timeoutMs = 3_000L)
            val output = result.stdout
            if (result.success && output.isNotBlank() && !output.contains("not logged in", ignoreCase = true)) {
                println("✅ Using GitHub token from GitHub CLI (gh)")
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
