package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.security.CredentialProviders
import ai.rever.bossterm.compose.util.SubprocessHelper
import java.io.File
import java.util.Properties

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
 * 3. local.properties file: GITHUB_TOKEN=ghp_...
 * 4. OS credential provider (service=bossterm.github, account=api_token)
 * 5. GitHub CLI (gh auth token)
 * 6. No token (fallback to unauthenticated access)
 */
object GitHubConfig {
    /**
     * GitHub Personal Access Token loaded from secure sources.
     * Attempts to use GitHub CLI if no token is explicitly configured.
     * Returns null if not configured (will use unauthenticated access).
     */
    val token: String? by lazy {
        // Try explicit configuration first
        getConfiguredToken() ?: getTokenFromGitHubCLI()
    }

    /**
     * Check if GitHub token is configured
     */
    val hasToken: Boolean
        get() = token != null

    /**
     * Get token from environment, system property, or local.properties
     */
    private fun getConfiguredToken(): String? {
        // 1. Environment variable
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. System property
        System.getProperty("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }

        // 3. local.properties file
        try {
            val localProps = File("local.properties")
            if (localProps.exists()) {
                val props = Properties()
                localProps.inputStream().use { props.load(it) }
                props.getProperty("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (e: Exception) {
            // Ignore errors reading local.properties
        }

        // 4. OS credential provider abstraction
        CredentialProviders.default.read(service = "bossterm.github", account = "api_token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    /**
     * Attempt to retrieve token from GitHub CLI (gh auth token)
     * Returns null if gh is not installed or not authenticated
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
