package ai.rever.bossterm.compose.platform

import ai.rever.bossterm.compose.util.UrlOpener
import kotlin.test.*

/**
 * Tests for centralized URL-open policy.
 *
 * Validates that UrlOpener enforces scheme restrictions consistently,
 * which prevents bypasses from code that previously used Desktop.browse() directly.
 */
class UrlOpenPolicyTest {

    @BeforeTest
    fun setup() {
        // Reset to default policy
        UrlOpener.setPolicy(UrlOpener.UrlOpenPolicy())
    }

    // ======================== Scheme Enforcement ========================

    @Test
    fun testBlockedSchemeIsRejected() {
        // javascript: URLs should be blocked (not in allowedSchemes)
        val blockedBefore = UrlOpener.blockedRequestCount()
        val result = UrlOpener.open("javascript:alert(1)")
        assertFalse(result, "javascript: scheme should be blocked")
        assertTrue(
            UrlOpener.blockedRequestCount() > blockedBefore,
            "Blocked count should increment"
        )
    }

    @Test
    fun testEmptyUrlIsRejected() {
        val blockedBefore = UrlOpener.blockedRequestCount()
        val result = UrlOpener.open("")
        assertFalse(result, "Empty URL should be rejected")
        assertTrue(UrlOpener.blockedRequestCount() > blockedBefore)
    }

    @Test
    fun testMalformedUrlIsRejected() {
        val blockedBefore = UrlOpener.blockedRequestCount()
        val result = UrlOpener.open("not a url at all !!!")
        assertFalse(result, "Malformed URL should be rejected")
        assertTrue(UrlOpener.blockedRequestCount() > blockedBefore)
    }

    @Test
    fun testCustomPolicyRestrictsSchemes() {
        // Set a policy that only allows https
        UrlOpener.setPolicy(
            UrlOpener.UrlOpenPolicy(
                allowedSchemes = setOf("https"),
                allowFileUrls = false
            )
        )

        val blockedBefore = UrlOpener.blockedRequestCount()
        val result = UrlOpener.open("http://example.com")
        assertFalse(result, "http: should be blocked when only https is allowed")
        assertTrue(UrlOpener.blockedRequestCount() > blockedBefore)
    }

    @Test
    fun testFileUrlBlockedByPolicy() {
        UrlOpener.setPolicy(
            UrlOpener.UrlOpenPolicy(allowFileUrls = false)
        )

        val blockedBefore = UrlOpener.blockedRequestCount()
        val result = UrlOpener.open("file:///etc/passwd")
        assertFalse(result, "file: URLs should be blocked when allowFileUrls is false")
        assertTrue(UrlOpener.blockedRequestCount() > blockedBefore)
    }

    // ======================== Policy Is Centralizable ========================

    @Test
    fun testDefaultPolicyAllowsStandardSchemes() {
        // Verify the default allowed schemes include the standard set
        val defaultPolicy = UrlOpener.UrlOpenPolicy()
        assertTrue("https" in defaultPolicy.allowedSchemes)
        assertTrue("http" in defaultPolicy.allowedSchemes)
        assertTrue("mailto" in defaultPolicy.allowedSchemes)
        assertTrue("file" in defaultPolicy.allowedSchemes)
        assertTrue("ssh" in defaultPolicy.allowedSchemes)
    }

    @Test
    fun testPolicyCanBeReplaced() {
        val restrictedPolicy = UrlOpener.UrlOpenPolicy(
            allowedSchemes = setOf("https")
        )
        UrlOpener.setPolicy(restrictedPolicy)

        // http should now be blocked
        val result = UrlOpener.open("http://example.com")
        assertFalse(result)

        // Restore default
        UrlOpener.setPolicy(UrlOpener.UrlOpenPolicy())
    }
}
