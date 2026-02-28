package ai.rever.bossterm.compose.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Manager for terminal settings with persistence support.
 * Settings are saved to ~/.bossterm/settings.json by default,
 * or to a custom path if specified.
 *
 * @param customSettingsPath Optional custom path for settings file.
 *        If null, uses default ~/.bossterm/settings.json
 */
class SettingsManager(private val customSettingsPath: String? = null) {
    private val _settings = MutableStateFlow(TerminalSettings.DEFAULT)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private var pendingSaveJob: Job? = null

    /**
     * Current settings as a StateFlow (reactive)
     */
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true  // Ensure all fields are written, not just non-default ones
    }

    private val settingsDir: File by lazy {
        if (customSettingsPath != null) {
            File(customSettingsPath).parentFile?.apply {
                if (!exists()) mkdirs()
            } ?: File(System.getProperty("user.home"), ".bossterm").apply {
                if (!exists()) mkdirs()
            }
        } else {
            File(System.getProperty("user.home"), ".bossterm").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    private val settingsFile: File by lazy {
        if (customSettingsPath != null) {
            File(customSettingsPath)
        } else {
            File(settingsDir, "settings.json")
        }
    }
    private val journalStore: SettingsJournalStore by lazy {
        SettingsJournalStore(settingsFile = settingsFile, json = json)
    }
    private var journalWritesSinceCompaction: Int = 0

    init {
        loadFromFile()
    }

    /**
     * Update settings and save to file
     */
    fun updateSettings(newSettings: TerminalSettings) {
        _settings.value = newSettings
        scheduleSaveToFile()
    }

    /**
     * Update a single setting field
     */
    fun updateSetting(updater: TerminalSettings.() -> TerminalSettings) {
        updateSettings(updater(_settings.value))
    }

    /**
     * Reset settings to defaults
     */
    fun resetToDefaults() {
        updateSettings(TerminalSettings.DEFAULT)
    }

    /**
     * Save current settings to file
     */
    fun saveToFile() {
        synchronized(saveLock) {
            pendingSaveJob?.cancel()
            pendingSaveJob = null
        }
        persistSettingsToFile(_settings.value)
    }

    private fun scheduleSaveToFile() {
        val job = saveScope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            persistSettingsToFile(_settings.value)
        }

        synchronized(saveLock) {
            pendingSaveJob?.cancel()
            pendingSaveJob = job
        }
    }

    private fun persistSettingsToFile(settingsSnapshot: TerminalSettings) {
        try {
            journalStore.append(settingsSnapshot)
            journalWritesSinceCompaction++

            val shouldCompact =
                !settingsFile.exists() ||
                    journalWritesSinceCompaction >= JOURNAL_COMPACTION_WRITE_THRESHOLD ||
                    journalStore.sizeBytes() >= JOURNAL_COMPACTION_SIZE_THRESHOLD_BYTES

            if (shouldCompact) {
                journalStore.compact(settingsSnapshot, backupSuffix = ".bak")
                journalWritesSinceCompaction = 0
                println("Settings compacted to: ${settingsFile.absolutePath}")
            }
        } catch (e: Exception) {
            System.err.println("Failed to save settings: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load settings from file.
     * After loading, re-saves to ensure any new fields (added in updates) are persisted
     * with their default values. This provides automatic settings migration.
     */
    fun loadFromFile() {
        try {
            var loadedSettings = TerminalSettings.DEFAULT
            var loadedFromSource = false

            if (settingsFile.exists()) {
                try {
                    val jsonString = settingsFile.readText()
                    loadedSettings = json.decodeFromString<TerminalSettings>(jsonString)
                    loadedFromSource = true
                    println("Settings loaded from: ${settingsFile.absolutePath}")
                } catch (e: Exception) {
                    System.err.println("Failed to parse settings file, will try journal: ${e.message}")
                }
            }

            val journalSettings = journalStore.loadLatestOrNull()
            if (journalSettings != null) {
                loadedSettings = journalSettings
                loadedFromSource = true
                println("Settings replayed from journal: ${settingsFile.absolutePath}.journal")
            }

            if (!loadedFromSource) {
                println("No settings state found, using defaults")
            }

            _settings.value = loadedSettings

            // Re-save to migrate settings files with any new fields added in updates.
            saveToFile()
        } catch (e: Exception) {
            System.err.println("Failed to load settings, using defaults: ${e.message}")
            e.printStackTrace()
            _settings.value = TerminalSettings.DEFAULT
        }
    }

    companion object {
        private const val SETTINGS_SAVE_DEBOUNCE_MS = 150L
        private const val JOURNAL_COMPACTION_WRITE_THRESHOLD = 32
        private const val JOURNAL_COMPACTION_SIZE_THRESHOLD_BYTES = 256 * 1024L

        /**
         * Global singleton instance using default settings path
         */
        val instance: SettingsManager by lazy { SettingsManager() }

        /**
         * Create a new SettingsManager with a custom settings file path.
         *
         * @param path Path to the settings JSON file
         * @return New SettingsManager instance using the custom path
         */
        fun withCustomPath(path: String): SettingsManager {
            return SettingsManager(customSettingsPath = path)
        }
    }
}
