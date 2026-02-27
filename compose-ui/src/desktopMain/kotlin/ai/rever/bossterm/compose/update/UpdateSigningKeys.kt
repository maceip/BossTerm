package ai.rever.bossterm.compose.update

/**
 * Embedded trust configuration for update signature verification.
 *
 * Release pipeline should set BOSSTERM_UPDATE_PUBLIC_KEY_PEM at build/runtime
 * or replace DEFAULT_UPDATE_PUBLIC_KEY_PEM with the production key.
 */
object UpdateSigningKeys {
    private const val DEFAULT_UPDATE_PUBLIC_KEY_PEM = ""

    val updateManifestPublicKeyPem: String
        get() {
            val fromProperty = System.getProperty("bossterm.update.publicKeyPem")
            if (!fromProperty.isNullOrBlank()) return fromProperty.trim()

            val fromEnv = System.getenv("BOSSTERM_UPDATE_PUBLIC_KEY_PEM")
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim()

            return DEFAULT_UPDATE_PUBLIC_KEY_PEM.trim()
        }
}
