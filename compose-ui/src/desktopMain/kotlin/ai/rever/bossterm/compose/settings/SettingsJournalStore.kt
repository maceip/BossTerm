package ai.rever.bossterm.compose.settings

import ai.rever.bossterm.compose.util.AtomicFileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class SettingsJournalEntry(
    val ts: Long,
    val settings: TerminalSettings
)

/**
 * Append-only settings journal with periodic compaction.
 */
internal class SettingsJournalStore(
    private val settingsFile: File,
    private val json: Json
) {
    private val journalJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()
    private val journalFile: File = File(settingsFile.parentFile, "${settingsFile.name}.journal")

    fun append(settings: TerminalSettings) {
        val line = journalJson.encodeToString(
            SettingsJournalEntry(
                ts = System.currentTimeMillis(),
                settings = settings
            )
        ) + "\n"
        synchronized(lock) {
            journalFile.parentFile?.mkdirs()
            journalFile.appendText(line)
        }
    }

    fun loadLatestOrNull(): TerminalSettings? {
        synchronized(lock) {
            if (!journalFile.exists()) return null
            val lines = try {
                journalFile.readLines()
            } catch (_: Exception) {
                return null
            }
            for (i in lines.indices.reversed()) {
                val line = lines[i].trim()
                if (line.isBlank()) continue
                try {
                    val entry = journalJson.decodeFromString<SettingsJournalEntry>(line)
                    return entry.settings
                } catch (_: Exception) {
                    continue
                }
            }
            return null
        }
    }

    fun compact(settings: TerminalSettings, backupSuffix: String = ".bak") {
        synchronized(lock) {
            val settingsJson = json.encodeToString(settings)
            AtomicFileWriter.writeTextAtomic(
                file = settingsFile,
                content = settingsJson,
                backupSuffix = backupSuffix
            )
            val compactedJournalLine = journalJson.encodeToString(
                SettingsJournalEntry(
                    ts = System.currentTimeMillis(),
                    settings = settings
                )
            ) + "\n"
            AtomicFileWriter.writeTextAtomic(
                file = journalFile,
                content = compactedJournalLine,
                backupSuffix = null
            )
        }
    }

    fun exists(): Boolean = journalFile.exists()

    fun sizeBytes(): Long = if (journalFile.exists()) journalFile.length() else 0L
}
