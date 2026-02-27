package ai.rever.bossterm.compose.cli

import kotlin.test.*

/**
 * Tests for CLIInstaller path safety validation.
 *
 * Validates that shell metacharacter injection is prevented in paths
 * used for privileged install/uninstall operations.
 */
class CLIInstallerPathSafetyTest {

    // ======================== Safe Paths ========================

    @Test
    fun testNormalUnixPathIsSafe() {
        // Standard Unix paths should pass validation
        CLIInstaller.validatePathSafe("/usr/local/bin/bossterm")
    }

    @Test
    fun testNormalWindowsPathIsSafe() {
        // Standard Windows paths should pass validation
        CLIInstaller.validatePathSafe("C:/Users/user/AppData/Local/BossTerm/bossterm.cmd")
    }

    @Test
    fun testPathWithSpacesIsSafe() {
        // Spaces are fine — they're handled by quoting in the helper script
        CLIInstaller.validatePathSafe("/usr/local/my apps/bossterm")
    }

    @Test
    fun testPathWithHyphensAndDotsIsSafe() {
        CLIInstaller.validatePathSafe("/opt/boss-term.v2/bin/bossterm")
    }

    // ======================== Dangerous Paths ========================

    @Test
    fun testSingleQuoteInjectionBlocked() {
        // Single quotes could break out of shell quoting
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/'; rm -rf /; echo '")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testDoubleQuoteInjectionBlocked() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/\"; rm -rf /; echo \"")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testBacktickInjectionBlocked() {
        // Backticks enable command substitution
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/`whoami`")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testDollarSignInjectionBlocked() {
        // Dollar sign enables variable expansion and $() subshells
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/\$(rm -rf /)")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testSemicolonInjectionBlocked() {
        // Semicolons chain shell commands
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe; rm -rf /")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testPipeInjectionBlocked() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe | malicious")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testAmpersandInjectionBlocked() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe & malicious")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testNewlineInjectionBlocked() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe\nmalicious")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testNullByteInjectionBlocked() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe\u0000malicious")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }

    @Test
    fun testBackslashInjectionBlocked() {
        // Backslashes can escape quotes in shell contexts
        val exception = assertFailsWith<IllegalArgumentException> {
            CLIInstaller.validatePathSafe("/tmp/safe\\malicious")
        }
        assertTrue(exception.message!!.contains("unsafe character"))
    }
}
