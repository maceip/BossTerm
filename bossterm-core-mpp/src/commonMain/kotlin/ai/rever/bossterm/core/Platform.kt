package ai.rever.bossterm.core

enum class Platform {
    Windows,
    macOS,
    Linux,
    Web,
    Unknown;

    companion object {
        private val current: Platform = detectPlatform()

        private fun detectPlatform(): Platform = detectCurrentPlatform()

        fun current(): Platform {
            return current
        }

        val isWindows: Boolean
            get() = current() == Windows

        val isMacOS: Boolean
            get() = current() == macOS

        val isLinux: Boolean
            get() = current() == Linux

        val isWeb: Boolean
            get() = current() == Web
    }
}

/**
 * Platform-specific detection. Implemented via expect/actual.
 */
internal expect fun detectCurrentPlatform(): Platform
