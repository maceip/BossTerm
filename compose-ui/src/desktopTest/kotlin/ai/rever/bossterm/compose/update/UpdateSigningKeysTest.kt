package ai.rever.bossterm.compose.update

import kotlin.test.*

/**
 * Tests for UpdateSigningKeys trust bootstrapping.
 *
 * Validates that update verification fails fast when no signing key is configured,
 * instead of silently accepting unverified updates (which the old empty-string
 * default allowed).
 */
class UpdateSigningKeysTest {

    @BeforeTest
    fun setup() {
        System.clearProperty("bossterm.update.publicKeyPem")
    }

    @AfterTest
    fun teardown() {
        System.clearProperty("bossterm.update.publicKeyPem")
    }

    // ======================== Fail-Fast Behavior ========================

    @Test
    fun testRequirePublicKeyPemThrowsWhenNotConfigured() {
        // With no system property, no env var, and no classpath resource,
        // requirePublicKeyPem() must throw rather than return empty string.
        // This prevents silent bypass of update signature verification.
        val exception = assertFailsWith<UpdateSigningKeys.UpdateKeyNotConfiguredException> {
            UpdateSigningKeys.requirePublicKeyPem()
        }
        assertTrue(exception.message!!.contains("No update signing key configured"))
    }

    @Test
    fun testIsKeyConfiguredReturnsFalseWhenNotConfigured() {
        assertFalse(UpdateSigningKeys.isKeyConfigured)
    }

    @Test
    fun testUpdateManifestPublicKeyPemReturnsNullWhenNotConfigured() {
        // The nullable getter returns null (not empty string like the old code)
        assertNull(UpdateSigningKeys.updateManifestPublicKeyPem)
    }

    // ======================== System Property Source ========================

    @Test
    fun testSystemPropertyKeyIsUsed() {
        val testKey = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n-----END PUBLIC KEY-----"
        System.setProperty("bossterm.update.publicKeyPem", testKey)

        assertTrue(UpdateSigningKeys.isKeyConfigured)
        assertEquals(testKey, UpdateSigningKeys.requirePublicKeyPem())
        assertEquals(testKey, UpdateSigningKeys.updateManifestPublicKeyPem)
    }

    @Test
    fun testBlankSystemPropertyIsIgnored() {
        System.setProperty("bossterm.update.publicKeyPem", "   ")
        assertNull(UpdateSigningKeys.updateManifestPublicKeyPem)
        assertFalse(UpdateSigningKeys.isKeyConfigured)
    }

    @Test
    fun testSystemPropertyChangeDetected() {
        // resolveKey() re-checks mutable sources each time
        assertFalse(UpdateSigningKeys.isKeyConfigured)

        System.setProperty("bossterm.update.publicKeyPem", "KEY_A")
        assertEquals("KEY_A", UpdateSigningKeys.updateManifestPublicKeyPem)

        System.setProperty("bossterm.update.publicKeyPem", "KEY_B")
        assertEquals("KEY_B", UpdateSigningKeys.updateManifestPublicKeyPem)
    }

    // ======================== Resolution Priority ========================

    @Test
    fun testResolveKeyReturnsNullByDefault() {
        val result = UpdateSigningKeys.resolveKey()
        if (System.getenv("BOSSTERM_UPDATE_PUBLIC_KEY_PEM").isNullOrBlank()) {
            assertNull(result)
        }
    }
}
