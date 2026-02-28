package ai.rever.bossterm.compose.util

object DebugLog {
    private fun isVerboseEnabled(): Boolean {
        val fromProperty = System.getProperty("bossterm.debug")
        if (!fromProperty.isNullOrBlank()) {
            return fromProperty.equals("1") || fromProperty.equals("true", ignoreCase = true)
        }

        val fromEnv = System.getenv("BOSSTERM_DEBUG")
        if (!fromEnv.isNullOrBlank()) {
            return fromEnv == "1" || fromEnv.equals("true", ignoreCase = true)
        }

        return false
    }

    fun info(message: String) {
        if (isVerboseEnabled()) {
            println(message)
        }
    }
}
