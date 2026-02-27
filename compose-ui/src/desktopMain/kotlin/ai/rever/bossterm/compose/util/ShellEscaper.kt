package ai.rever.bossterm.compose.util

/**
 * Centralized escaping helpers for building shell command strings.
 *
 * Prefer argv-style ProcessBuilder APIs where possible. Use these only when
 * a shell string is unavoidable.
 */
object ShellEscaper {
    fun escapePosix(token: String): String {
        return "'${token.replace("'", "'\"'\"'")}'"
    }

    fun escapePowerShell(token: String): String {
        // Single-quote string escaping for PowerShell.
        return "'${token.replace("'", "''")}'"
    }
}
