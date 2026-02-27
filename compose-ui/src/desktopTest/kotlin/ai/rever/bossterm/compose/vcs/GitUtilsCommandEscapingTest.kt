package ai.rever.bossterm.compose.vcs

import kotlin.test.Test
import kotlin.test.assertEquals

class GitUtilsCommandEscapingTest {

    @Test
    fun gitCommand_escapesSingleQuotesInCwd() {
        val cwd = "/tmp/it's-dangerous"
        val command = GitUtils.gitCommand("status", cwd)

        assertEquals("git -C '/tmp/it'\"'\"'s-dangerous' status\n", command)
    }

    @Test
    fun ghCommand_escapesShellMetacharactersInCwd() {
        val cwd = "/tmp/repo; rm -rf /"
        val command = GitUtils.ghCommand("pr list", cwd)

        assertEquals("cd '/tmp/repo; rm -rf /' && gh pr list\n", command)
    }
}
