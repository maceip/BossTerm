package ai.rever.bossterm.compose.tabs

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Injects BossTerm shell integration into shell processes.
 *
 * This uses the same approach as iTerm2:
 * - For Zsh: Hijack ZDOTDIR to point to our integration directory
 * - For Bash: Set ENV to point to our loader script
 * - For Fish: Prepend our vendor_conf.d to XDG_DATA_DIRS
 *
 * The integration scripts send OSC 133 sequences to track command execution,
 * enabling features like command completion notifications.
 */
object ShellIntegrationInjector {

    private val LOG = LoggerFactory.getLogger(ShellIntegrationInjector::class.java)

    // Resource paths for shell integration scripts
    private val SHELL_INTEGRATION_RESOURCES = listOf(
        ".zshenv",
        "bossterm_shell_integration.zsh",
        "bash-loader",
        "bossterm_shell_integration.bash",
        "fish/vendor_conf.d/bossterm_shell_integration.fish"
    )
    private const val RESOURCE_MANIFEST_NAME = "manifest.sha256"

    // Lazy-initialized integration directory
    private val integrationDir: File by lazy {
        File(System.getProperty("user.home"), ".bossterm/shell-integration").also { dir ->
            extractAllResources(dir)
        }
    }

    /**
     * Inject shell integration environment variables for the given shell.
     *
     * @param shell The shell command/path (e.g., "/bin/zsh", "bash", etc.)
     * @param env The environment map to modify
     * @param enabled Whether shell integration is enabled (from settings)
     */
    fun injectForShell(shell: String, env: MutableMap<String, String>, enabled: Boolean = true) {
        if (!enabled) {
            LOG.debug("Shell integration disabled, skipping injection")
            return
        }
        if (isShellIntegrationSafeModeEnabled()) {
            LOG.info("Shell integration safe mode enabled; skipping integration injection")
            return
        }

        val shellName = File(shell).name

        // If the command is 'login' (macOS uses /usr/bin/login as wrapper),
        // detect the actual shell from SHELL environment variable
        val effectiveShellName = if (shellName == "login") {
            val userShell = env["SHELL"] ?: System.getenv("SHELL") ?: ""
            File(userShell).name
        } else {
            shellName
        }

        LOG.debug("Injecting shell integration for: $effectiveShellName")

        when {
            effectiveShellName == "zsh" || effectiveShellName.endsWith("zsh") -> injectZsh(env)
            effectiveShellName == "bash" || effectiveShellName.endsWith("bash") -> injectBash(env)
            effectiveShellName == "fish" || effectiveShellName.endsWith("fish") -> injectFish(env)
            else -> LOG.debug("Unknown shell '$effectiveShellName', shell integration not injected")
        }
    }

    /**
     * Inject for Zsh using ZDOTDIR hijacking.
     *
     * How it works:
     * 1. Save original ZDOTDIR (if any) to BOSSTERM_ORIG_ZDOTDIR
     * 2. Set ZDOTDIR to our integration directory
     * 3. Zsh reads .zshenv from our directory first
     * 4. Our .zshenv restores original ZDOTDIR and sources user's config
     * 5. Then loads shell integration for interactive shells
     */
    private fun injectZsh(env: MutableMap<String, String>) {
        // Save original ZDOTDIR if it exists
        env["ZDOTDIR"]?.let { original ->
            env["BOSSTERM_ORIG_ZDOTDIR"] = original
        }

        // Point ZDOTDIR to our integration directory
        env["ZDOTDIR"] = integrationDir.absolutePath
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"

        LOG.debug("Injected Zsh integration via ZDOTDIR=${integrationDir.absolutePath}")
    }

    /**
     * Inject for Bash using ENV variable.
     *
     * How it works:
     * 1. Set ENV to point to our bash-loader script
     * 2. Bash sources ENV for interactive shells
     * 3. Our loader sources normal startup files first
     * 4. Then loads shell integration
     */
    private fun injectBash(env: MutableMap<String, String>) {
        val loaderPath = File(integrationDir, "bash-loader").absolutePath

        // Set ENV to our loader script
        env["ENV"] = loaderPath
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"

        LOG.debug("Injected Bash integration via ENV=$loaderPath")
    }

    /**
     * Inject for Fish using XDG_DATA_DIRS.
     *
     * How it works:
     * 1. Prepend our fish directory to XDG_DATA_DIRS
     * 2. Fish discovers vendor_conf.d/bossterm_shell_integration.fish
     * 3. Fish autoloads it during initialization
     */
    private fun injectFish(env: MutableMap<String, String>) {
        val fishDir = File(integrationDir, "fish").absolutePath
        val existingDirs = env["XDG_DATA_DIRS"] ?: "/usr/local/share:/usr/share"

        // Prepend our fish directory
        env["XDG_DATA_DIRS"] = "$fishDir:$existingDirs"
        env["BOSSTERM_INJECT_INTEGRATION"] = "1"

        LOG.debug("Injected Fish integration via XDG_DATA_DIRS")
    }

    private fun isShellIntegrationSafeModeEnabled(): Boolean {
        val prop = System.getProperty("bossterm.shellIntegration.safeMode")
        if (!prop.isNullOrBlank()) {
            return prop == "1" || prop.equals("true", ignoreCase = true)
        }
        val envValue = System.getenv("BOSSTERM_SHELL_INTEGRATION_SAFE_MODE")
        if (!envValue.isNullOrBlank()) {
            return envValue == "1" || envValue.equals("true", ignoreCase = true)
        }
        return false
    }

    /**
     * Extract all shell integration resources to the integration directory.
     */
    private fun extractAllResources(targetDir: File) {
        // Create target directory
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val expectedHashes = computeExpectedResourceHashes()
        val manifestFile = File(targetDir, RESOURCE_MANIFEST_NAME)

        if (isCurrentExtractionValid(targetDir, manifestFile, expectedHashes)) {
            return
        }

        for (resource in SHELL_INTEGRATION_RESOURCES) {
            val targetFile = File(targetDir, resource)

            // Create parent directories if needed
            targetFile.parentFile?.mkdirs()

            // Extract resource (always overwrite to ensure latest version)
            try {
                val resourcePath = "shell-integration/$resource"
                val bytes = readResourceBytes(resourcePath)

                if (bytes != null) {
                    targetFile.outputStream().use { output ->
                        output.write(bytes)
                    }
                    // Make scripts executable
                    if (resource.endsWith(".bash") || resource.endsWith(".zsh") ||
                        resource.endsWith(".fish") || resource == "bash-loader" ||
                        resource == ".zshenv") {
                        targetFile.setExecutable(true)
                    }
                } else {
                    System.err.println("[ShellIntegration] Resource not found: $resourcePath")
                }
            } catch (e: Exception) {
                System.err.println("[ShellIntegration] Error extracting $resource: ${e.message}")
            }
        }

        writeManifest(manifestFile, expectedHashes)
    }

    private fun computeExpectedResourceHashes(): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        for (resource in SHELL_INTEGRATION_RESOURCES) {
            val resourcePath = "shell-integration/$resource"
            val bytes = readResourceBytes(resourcePath)
            if (bytes == null) {
                System.err.println("[ShellIntegration] Resource not found for manifest: $resourcePath")
                continue
            }
            hashes[resource] = sha256(bytes)
        }
        return hashes
    }

    private fun readResourceBytes(resourcePath: String): ByteArray? {
        val inputStream = object {}.javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
            ?: ShellIntegrationInjector::class.java.classLoader?.getResourceAsStream(resourcePath)
        return inputStream?.use { it.readBytes() }
    }

    private fun isCurrentExtractionValid(
        targetDir: File,
        manifestFile: File,
        expectedHashes: Map<String, String>
    ): Boolean {
        if (!manifestFile.exists()) {
            return false
        }

        val existingManifest = readManifest(manifestFile)
        if (existingManifest != expectedHashes) {
            return false
        }

        for ((resource, expectedHash) in expectedHashes) {
            val extractedFile = File(targetDir, resource)
            if (!extractedFile.exists()) {
                return false
            }
            if (!sha256(extractedFile).equals(expectedHash, ignoreCase = true)) {
                return false
            }
        }

        return true
    }

    private fun readManifest(manifestFile: File): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        return try {
            manifestFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val firstSpace = line.indexOf(' ')
                    if (firstSpace > 0 && firstSpace + 1 < line.length) {
                        val hash = line.substring(0, firstSpace).trim().lowercase()
                        val path = line.substring(firstSpace + 1).trim()
                        if (path.isNotEmpty()) {
                            hashes[path] = hash
                        }
                    }
                }
            hashes
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeManifest(manifestFile: File, hashes: Map<String, String>) {
        val content = buildString {
            appendLine("# BossTerm shell integration resource manifest")
            hashes.toSortedMap().forEach { (path, hash) ->
                appendLine("$hash $path")
            }
        }
        manifestFile.writeText(content)
    }

    private fun sha256(file: File): String {
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
