package ai.rever.bossterm.compose.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * Best-effort structured audit logger for security-sensitive actions.
 */
object AuditLogger {
    private val json = Json { encodeDefaults = true }
    private val lock = Any()
    private val auditDir: File by lazy { File(System.getProperty("user.home"), ".bossterm").also { it.mkdirs() } }
    private val auditFile: File by lazy { File(auditDir, "audit.log") }

    fun log(event: String, outcome: String, details: Map<String, String> = emptyMap()) {
        val entry = buildJsonObject {
            put("ts", Instant.now().toString())
            put("event", event)
            put("outcome", outcome)
            details.forEach { (k, v) -> put(k, v) }
        }
        val line = json.encodeToString(entry) + "\n"

        synchronized(lock) {
            try {
                auditFile.appendText(line)
            } catch (_: Exception) {
                // Best effort; don't impact user flows on logging failures.
            }
        }
    }
}
