package ai.rever.bossterm.compose.update

/**
 * Embedded trust configuration for update signature verification.
 *
 * The public key is resolved from (in priority order):
 * 1. System property: bossterm.update.publicKeyPem
 * 2. Environment variable: BOSSTERM_UPDATE_PUBLIC_KEY_PEM
 * 3. Classpath resource: /update-signing-key.pem
 *
 * If none of these sources provide a key, [isKeyConfigured] returns false and
 * [requirePublicKeyPem] throws [UpdateKeyNotConfiguredException].
 * This fail-fast approach prevents silent verification bypass when the build
 * pipeline forgets to embed the production key.
 */
object UpdateSigningKeys {

    /**
     * Exception thrown when update verification is attempted without a configured key.
     */
    class UpdateKeyNotConfiguredException(
        message: String = "No update signing key configured. " +
            "Set system property 'bossterm.update.publicKeyPem', " +
            "environment variable 'BOSSTERM_UPDATE_PUBLIC_KEY_PEM', " +
            "or bundle '/update-signing-key.pem' on the classpath."
    ) : RuntimeException(message)

    /**
     * Classpath resource key, cached once (immutable at runtime).
     */
    private val classpathKey: String? by lazy { loadClasspathKey() }

    /**
     * Whether a usable signing key is available.
     * Re-checks mutable sources (system property) each time.
     */
    val isKeyConfigured: Boolean
        get() = !resolveKey().isNullOrBlank()

    /**
     * The public key PEM string, or null if not configured.
     * Re-checks mutable sources (system property) each time.
     */
    val updateManifestPublicKeyPem: String?
        get() = resolveKey()

    /**
     * Returns the public key PEM string, or throws if not configured.
     * Use this in update verification paths to fail fast.
     */
    fun requirePublicKeyPem(): String {
        return resolveKey()?.takeIf { it.isNotBlank() }
            ?: throw UpdateKeyNotConfiguredException()
    }

    /**
     * Resolve the key from all sources, in priority order.
     * System property and env var are checked fresh each time (they can change).
     * Classpath resource is cached since it's immutable once the JVM starts.
     */
    internal fun resolveKey(): String? {
        // 1. System property (highest priority — set by build or test harness)
        System.getProperty("bossterm.update.publicKeyPem")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 2. Environment variable (CI / runtime injection)
        System.getenv("BOSSTERM_UPDATE_PUBLIC_KEY_PEM")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 3. Classpath resource (embedded by release pipeline)
        return classpathKey
    }

    private fun loadClasspathKey(): String? {
        return try {
            val stream = UpdateSigningKeys::class.java.getResourceAsStream("/update-signing-key.pem")
            if (stream != null) {
                val pem = stream.bufferedReader().use { it.readText().trim() }
                if (pem.isNotBlank()) pem else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
