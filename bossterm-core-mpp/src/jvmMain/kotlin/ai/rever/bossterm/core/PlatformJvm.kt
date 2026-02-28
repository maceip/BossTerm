package ai.rever.bossterm.core

internal actual fun detectCurrentPlatform(): Platform {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("win") -> Platform.Windows
        osName.contains("mac") -> Platform.macOS
        osName.contains("nux") || osName.contains("nix") -> Platform.Linux
        else -> Platform.Unknown
    }
}
