package ai.rever.bossterm.compose.util

import java.io.File

object LogRedactor {
    private val homeDir = System.getProperty("user.home")?.let { File(it).absolutePath } ?: ""

    fun redactPath(value: String): String {
        if (homeDir.isBlank()) return value
        return value.replace(homeDir, "~")
    }
}
