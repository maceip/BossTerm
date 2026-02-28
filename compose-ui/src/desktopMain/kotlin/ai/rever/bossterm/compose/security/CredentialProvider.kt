package ai.rever.bossterm.compose.security

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.util.SubprocessHelper

/**
 * Credential provider abstraction for sensitive tokens.
 */
interface CredentialProvider {
    fun read(service: String, account: String): String?
    fun write(service: String, account: String, secret: String): Boolean
    fun delete(service: String, account: String): Boolean
}

/**
 * Best-effort desktop credential provider.
 *
 * Uses platform-native stores when available:
 * - macOS: `security`
 * - Linux: `secret-tool`
 * - Windows: in-memory fallback (no built-in secure retrieval primitive)
 */
class DesktopCredentialProvider : CredentialProvider {
    private val memoryFallback = mutableMapOf<String, String>()

    override fun read(service: String, account: String): String? {
        return when {
            ShellCustomizationUtils.isMacOS() -> readFromMacKeychain(service, account)
            ShellCustomizationUtils.isLinux() -> readFromLinuxSecretTool(service, account)
            else -> memoryFallback[key(service, account)]
        }
    }

    override fun write(service: String, account: String, secret: String): Boolean {
        return when {
            ShellCustomizationUtils.isMacOS() -> writeToMacKeychain(service, account, secret)
            ShellCustomizationUtils.isLinux() -> writeToLinuxSecretTool(service, account, secret)
            else -> {
                memoryFallback[key(service, account)] = secret
                true
            }
        }
    }

    override fun delete(service: String, account: String): Boolean {
        return when {
            ShellCustomizationUtils.isMacOS() -> deleteFromMacKeychain(service, account)
            ShellCustomizationUtils.isLinux() -> deleteFromLinuxSecretTool(service, account)
            else -> memoryFallback.remove(key(service, account)) != null
        }
    }

    private fun key(service: String, account: String): String = "$service::$account"

    private fun readFromMacKeychain(service: String, account: String): String? {
        return runCommandAndCapture(
            "security",
            "find-generic-password",
            "-s",
            service,
            "-a",
            account,
            "-w"
        )?.takeIf { it.isNotBlank() }
    }

    private fun writeToMacKeychain(service: String, account: String, secret: String): Boolean {
        return runCommand(
            "security",
            "add-generic-password",
            "-s",
            service,
            "-a",
            account,
            "-w",
            secret,
            "-U"
        )
    }

    private fun deleteFromMacKeychain(service: String, account: String): Boolean {
        return runCommand(
            "security",
            "delete-generic-password",
            "-s",
            service,
            "-a",
            account
        )
    }

    private fun readFromLinuxSecretTool(service: String, account: String): String? {
        return runCommandAndCapture(
            "secret-tool",
            "lookup",
            "service",
            service,
            "account",
            account
        )?.takeIf { it.isNotBlank() }
    }

    private fun writeToLinuxSecretTool(service: String, account: String, secret: String): Boolean {
        return SubprocessHelper.run(
            args = listOf(
                "secret-tool",
                "store",
                "--label=$service",
                "service",
                service,
                "account",
                account
            ),
            timeoutMs = 3_000L,
            stdinText = "$secret\n"
        ).success
    }

    private fun deleteFromLinuxSecretTool(service: String, account: String): Boolean {
        return runCommand(
            "secret-tool",
            "clear",
            "service",
            service,
            "account",
            account
        )
    }

    private fun runCommand(vararg args: String): Boolean {
        return SubprocessHelper.run(args = args.toList(), timeoutMs = 3_000L).success
    }

    private fun runCommandAndCapture(vararg args: String): String? {
        val result = SubprocessHelper.run(args = args.toList(), timeoutMs = 3_000L)
        return if (result.success) result.stdout else null
    }
}

object CredentialProviders {
    val default: CredentialProvider by lazy { DesktopCredentialProvider() }
}
