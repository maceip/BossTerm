package ai.rever.bossterm.compose.util

import java.io.File
import java.util.concurrent.TimeUnit

data class SubprocessResult(
    val exitCode: Int?,
    val stdout: String,
    val timedOut: Boolean
) {
    val success: Boolean
        get() = !timedOut && exitCode == 0
}

/**
 * Shared subprocess helper with timeout and output capture.
 */
object SubprocessHelper {
    fun run(
        vararg args: String,
        timeoutMs: Long = 2_000L,
        workingDirectory: File? = null,
        stdinText: String? = null
    ): SubprocessResult {
        return run(
            args = args.toList(),
            timeoutMs = timeoutMs,
            workingDirectory = workingDirectory,
            stdinText = stdinText
        )
    }

    fun run(
        args: List<String>,
        timeoutMs: Long = 2_000L,
        workingDirectory: File? = null,
        stdinText: String? = null
    ): SubprocessResult {
        if (args.isEmpty()) {
            return SubprocessResult(exitCode = null, stdout = "", timedOut = false)
        }

        var process: Process? = null
        return try {
            process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(workingDirectory)
                .start()

            if (stdinText != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdinText)
                    writer.flush()
                }
            } else {
                process.outputStream.close()
            }

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return SubprocessResult(exitCode = null, stdout = "", timedOut = true)
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            SubprocessResult(
                exitCode = process.exitValue(),
                stdout = output,
                timedOut = false
            )
        } catch (_: Exception) {
            SubprocessResult(exitCode = null, stdout = "", timedOut = false)
        } finally {
            try {
                process?.inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                process?.errorStream?.close()
            } catch (_: Exception) {
            }
            try {
                process?.outputStream?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun commandExists(command: String, timeoutMs: Long = 2_000L, windows: Boolean = isWindows()): Boolean {
        val probeCommand = if (windows) "where" else "which"
        return run(probeCommand, command, timeoutMs = timeoutMs).success
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }
}
