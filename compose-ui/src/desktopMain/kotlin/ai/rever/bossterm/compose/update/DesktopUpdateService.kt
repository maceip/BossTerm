package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.util.LogRedactor
import ai.rever.bossterm.compose.util.DebugLog
import ai.rever.bossterm.compose.util.AuditLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Desktop implementation of update service using GitHub Releases API.
 */
class DesktopUpdateService {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val apiClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    private val downloadClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 900_000  // 15 minutes for large downloads
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val RELEASES_REPO = "kshivang/BossTerm"
        private const val RELEASES_ENDPOINT = "$GITHUB_API_BASE/repos/$RELEASES_REPO/releases"
        private val CHECKSUM_MANIFEST_NAMES = setOf("sha256sums", "sha256sums.txt", "checksums.txt")
        private val CHECKSUM_SIGNATURE_NAMES = setOf("sha256sums.asc", "sha256sums.sig", "checksums.txt.asc", "checksums.txt.sig")
    }

    private val HASH_PREFIX_REGEX = Regex("^([A-Fa-f0-9]{64})\\s+\\*?(.+)$")
    private val OPENSSL_STYLE_REGEX = Regex("^SHA256\\s*\\((.+)\\)\\s*=\\s*([A-Fa-f0-9]{64})$")
    private val BASE64_TEXT_REGEX = Regex("^[A-Za-z0-9+/=\\s]+$")

    /**
     * Fetch all available releases from GitHub.
     * Used for version selection in About section.
     */
    suspend fun getAllReleases(includePreReleases: Boolean = false): Result<List<GitHubRelease>> {
        return try {
            val response = apiClient.get(RELEASES_ENDPOINT) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BossTerm-Desktop-${Version.CURRENT}")
                    GitHubConfig.token?.let { append("Authorization", "Bearer $it") }
                }
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                val errorMessage = when {
                    errorBody.contains("rate limit", ignoreCase = true) ->
                        "GitHub API rate limit exceeded. Please try again later."
                    else -> "Unable to fetch releases (HTTP ${response.status.value})"
                }
                return Result.failure(Exception(errorMessage))
            }

            val releases = response.body<List<GitHubRelease>>()
            val filteredReleases = releases
                .filter { !it.draft && (includePreReleases || !it.prerelease) }
                .sortedByDescending { Version.parse(it.tag_name) }

            Result.success(filteredReleases)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download a specific release version.
     * Returns the path to the downloaded file.
     */
    suspend fun downloadRelease(
        release: GitHubRelease,
        onProgress: (progress: Float) -> Unit
    ): Result<String> {
        val version = Version.parse(release.tag_name)
            ?: return Result.failure(Exception("Invalid version: ${release.tag_name}"))

        val expectedAssetName = getExpectedAssetName(version)
        val asset = release.assets.find { it.name.equals(expectedAssetName, ignoreCase = true) }
            ?: return Result.failure(Exception("No asset available for ${getCurrentPlatform()}"))

        val downloadUrl = asset.browser_download_url
            ?: return Result.failure(Exception("No download URL for asset"))
        val checksumManifestUrl = findChecksumManifestAsset(release.assets)?.browser_download_url
            ?: return Result.failure(Exception("Release is missing SHA-256 manifest; refusing to install unsigned update"))
        val checksumSignatureUrl = findChecksumSignatureAsset(release.assets)?.browser_download_url
            ?: return Result.failure(Exception("Release is missing detached checksum signature; refusing to install unsigned update"))
        if (!isSigningKeyConfigured()) {
            return Result.failure(Exception("Update signing key is not configured; refusing to install update"))
        }

        return try {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, asset.name)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            downloadToFileWithProgress(
                downloadUrl = downloadUrl,
                outputFile = downloadFile,
                fallbackSize = asset.size,
                onProgress = onProgress
            )

            verifyDownloadedAsset(
                downloadFile = downloadFile,
                expectedAssetName = asset.name,
                checksumManifestUrl = checksumManifestUrl,
                checksumSignatureUrl = checksumSignatureUrl
            ).getOrElse { error ->
                downloadFile.delete()
                return Result.failure(error)
            }

            if (downloadFile.exists() && downloadFile.length() > 0) {
                AuditLogger.log(
                    "update_download",
                    "success",
                    mapOf("asset" to asset.name, "path" to LogRedactor.redactPath(downloadFile.absolutePath))
                )
                Result.success(downloadFile.absolutePath)
            } else {
                AuditLogger.log("update_download", "failed", mapOf("asset" to asset.name, "reason" to "empty_file"))
                Result.failure(Exception("Download failed: file is empty"))
            }
        } catch (e: Exception) {
            AuditLogger.log("update_download", "failed", mapOf("asset" to asset.name, "error" to (e.message ?: "unknown")))
            Result.failure(e)
        }
    }

    /**
     * Check for available updates.
     */
    suspend fun checkForUpdates(): UpdateInfo {
        return try {
            if (GitHubConfig.hasToken) {
                DebugLog.info("✅ Using authenticated GitHub API (5,000 requests/hour)")
            } else {
                DebugLog.info("⚠️ Using unauthenticated GitHub API (60 requests/hour)")
            }

            val response = apiClient.get(RELEASES_ENDPOINT) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BossTerm-Desktop-${Version.CURRENT}")
                    GitHubConfig.token?.let { append("Authorization", "Bearer $it") }
                }
            }

            if (response.status.value !in 200..299) {
                val errorBody = response.bodyAsText()
                val errorMessage = when {
                    errorBody.contains("rate limit", ignoreCase = true) ->
                        "GitHub API rate limit exceeded. Please try again later."
                    else -> "Unable to check for updates (HTTP ${response.status.value})"
                }
                println("Update check failed: $errorMessage")
                return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )
            }

            val releases = response.body<List<GitHubRelease>>()

            val latestRelease = releases
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { release ->
                    Version.parse(release.tag_name)?.let { version -> release to version }
                }
                .maxByOrNull { it.second }
                ?.first

            if (latestRelease == null) {
                return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )
            }

            val latestVersion = Version.parse(latestRelease.tag_name)
                ?: return UpdateInfo(
                    available = false,
                    currentVersion = Version.CURRENT,
                    latestVersion = Version.CURRENT,
                    releaseNotes = ""
                )

            val isUpdateAvailable = latestVersion.isNewerThan(Version.CURRENT)

            val expectedAssetName = getExpectedAssetName(latestVersion)
            DebugLog.info("Looking for asset: $expectedAssetName")
            DebugLog.info("Available assets: ${latestRelease.assets.map { it.name }}")

            val asset = latestRelease.assets.find {
                it.name.equals(expectedAssetName, ignoreCase = true)
            }
            val checksumManifestAsset = findChecksumManifestAsset(latestRelease.assets)
            val checksumSignatureAsset = findChecksumSignatureAsset(latestRelease.assets)
            val signingKeyConfigured = isSigningKeyConfigured()

            // Only show update available if the asset exists for this platform
            // (handles library-only releases that have no platform binaries)
            if (asset == null && isUpdateAvailable) {
                println("⚠️ Update v$latestVersion exists but no asset for ${getCurrentPlatform()}")
            }
            if (asset != null && isUpdateAvailable && checksumManifestAsset == null) {
                println("⚠️ Update v$latestVersion is missing SHA-256 manifest; blocking automatic update")
            }
            if (asset != null && isUpdateAvailable && checksumSignatureAsset == null) {
                println("⚠️ Update v$latestVersion is missing detached signature; blocking automatic update")
            }
            if (asset != null && isUpdateAvailable && !signingKeyConfigured) {
                println("⚠️ Update signing key is not configured; blocking automatic update")
            }

            UpdateInfo(
                available = isUpdateAvailable &&
                    asset != null &&
                    checksumManifestAsset != null &&
                    checksumSignatureAsset != null &&
                    signingKeyConfigured,
                currentVersion = Version.CURRENT,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = asset?.browser_download_url,
                assetSize = asset?.size ?: 0,
                assetName = asset?.name ?: "",
                checksumManifestUrl = checksumManifestAsset?.browser_download_url,
                checksumSignatureUrl = checksumSignatureAsset?.browser_download_url
            )
        } catch (e: Exception) {
            println("Error checking for updates: ${e.message}")
            UpdateInfo(
                available = false,
                currentVersion = Version.CURRENT,
                latestVersion = Version.CURRENT,
                releaseNotes = ""
            )
        }
    }

    /**
     * Download an update.
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float) -> Unit
    ): String? {
        return try {
            val downloadUrl = updateInfo.downloadUrl ?: return null
            val checksumManifestUrl = updateInfo.checksumManifestUrl ?: return null
            val checksumSignatureUrl = updateInfo.checksumSignatureUrl ?: return null
            if (!isSigningKeyConfigured()) {
                println("Update signing key is not configured; refusing to download update")
                return null
            }

            println("Starting download from: $downloadUrl")
            val tempDir = File(System.getProperty("java.io.tmpdir"), "bossterm-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, updateInfo.assetName)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            downloadToFileWithProgress(
                downloadUrl = downloadUrl,
                outputFile = downloadFile,
                fallbackSize = updateInfo.assetSize,
                onProgress = onProgress
            )

            verifyDownloadedAsset(
                downloadFile = downloadFile,
                expectedAssetName = updateInfo.assetName,
                checksumManifestUrl = checksumManifestUrl,
                checksumSignatureUrl = checksumSignatureUrl
            ).getOrElse { error ->
                println("Integrity verification failed: ${error.message}")
                downloadFile.delete()
                return null
            }

            if (downloadFile.exists() && downloadFile.length() > 0) {
                println("Update downloaded successfully: ${LogRedactor.redactPath(downloadFile.absolutePath)}")
                AuditLogger.log(
                    "update_download",
                    "success",
                    mapOf("asset" to updateInfo.assetName, "path" to LogRedactor.redactPath(downloadFile.absolutePath))
                )
                downloadFile.absolutePath
            } else {
                AuditLogger.log("update_download", "failed", mapOf("asset" to updateInfo.assetName, "reason" to "empty_file"))
                null
            }
        } catch (e: Exception) {
            println("Error downloading update: ${e.message}")
            AuditLogger.log("update_download", "failed", mapOf("asset" to updateInfo.assetName, "error" to (e.message ?: "unknown")))
            null
        }
    }

    private fun findChecksumManifestAsset(assets: List<GitHubAsset>): GitHubAsset? {
        return assets.firstOrNull { it.name.lowercase() in CHECKSUM_MANIFEST_NAMES }
    }

    private fun findChecksumSignatureAsset(assets: List<GitHubAsset>): GitHubAsset? {
        return assets.firstOrNull { it.name.lowercase() in CHECKSUM_SIGNATURE_NAMES }
    }

    private suspend fun downloadToFileWithProgress(
        downloadUrl: String,
        outputFile: File,
        fallbackSize: Long,
        onProgress: (progress: Float) -> Unit
    ) {
        val response = downloadClient.get(downloadUrl)
        if (response.status.value !in 200..299) {
            throw Exception("Download failed (HTTP ${response.status.value})")
        }

        val totalSize = response.headers["Content-Length"]?.toLongOrNull() ?: fallbackSize
        withContext(Dispatchers.IO) {
            val channel = response.bodyAsChannel()
            val outputStream = FileOutputStream(outputFile)

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)
            var lastProgressUpdate = 0L

            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val shouldUpdateProgress = if (totalSize > 0) {
                        downloadedBytes - lastProgressUpdate >= 262144 ||
                            (downloadedBytes.toFloat() / totalSize - lastProgressUpdate.toFloat() / totalSize) >= 0.05f
                    } else {
                        downloadedBytes - lastProgressUpdate >= 131072
                    }

                    if (shouldUpdateProgress) {
                        val progress = if (totalSize > 0) {
                            (downloadedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0.1f + (downloadedBytes / 1048576f % 0.8f)
                        }

                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                        lastProgressUpdate = downloadedBytes
                    }
                }
            }

            outputStream.close()
            channel.cancel()

            withContext(Dispatchers.Main) {
                onProgress(1f)
            }
        }
    }

    private suspend fun verifyDownloadedAsset(
        downloadFile: File,
        expectedAssetName: String,
        checksumManifestUrl: String,
        checksumSignatureUrl: String
    ): Result<Unit> {
        return try {
            val checksumManifestBytes = fetchBinaryResource(checksumManifestUrl)
            val checksumSignatureBytes = fetchBinaryResource(checksumSignatureUrl)
            if (!verifyManifestSignature(checksumManifestBytes, checksumSignatureBytes)) {
                AuditLogger.log("update_verify", "failed", mapOf("asset" to expectedAssetName, "reason" to "signature_invalid"))
                return Result.failure(Exception("Detached signature verification failed for checksum manifest"))
            }

            val checksumManifest = checksumManifestBytes.toString(Charsets.UTF_8)
            val expectedHash = parseChecksumManifest(checksumManifest, expectedAssetName)
                ?: return Result.failure(Exception("SHA-256 manifest does not include $expectedAssetName"))

            val actualHash = sha256(downloadFile)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                AuditLogger.log("update_verify", "failed", mapOf("asset" to expectedAssetName, "reason" to "sha256_mismatch"))
                return Result.failure(
                    Exception("SHA-256 mismatch for ${downloadFile.name}: expected $expectedHash, got $actualHash")
                )
            }

            println("✅ Integrity verified (${downloadFile.name}) via signed SHA-256 manifest")
            AuditLogger.log("update_verify", "success", mapOf("asset" to expectedAssetName))
            Result.success(Unit)
        } catch (e: Exception) {
            AuditLogger.log("update_verify", "failed", mapOf("asset" to expectedAssetName, "error" to (e.message ?: "unknown")))
            Result.failure(e)
        }
    }

    private suspend fun fetchBinaryResource(url: String): ByteArray {
        val response = downloadClient.get(url)
        if (response.status.value !in 200..299) {
            throw Exception("Failed to fetch $url (HTTP ${response.status.value})")
        }
        return response.body()
    }

    private fun isSigningKeyConfigured(): Boolean {
        return UpdateSigningKeys.updateManifestPublicKeyPem.isNotBlank()
    }

    private fun verifyManifestSignature(manifestBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        val keyPem = UpdateSigningKeys.updateManifestPublicKeyPem
        if (keyPem.isBlank()) {
            return false
        }

        val publicKey = parseRsaPublicKey(keyPem)
        val normalizedSignature = normalizeSignatureBytes(signatureBytes)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        return verifier.verify(normalizedSignature)
    }

    private fun parseRsaPublicKey(pem: String): java.security.PublicKey {
        val base64Body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(base64Body)
        val spec = X509EncodedKeySpec(decoded)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private fun normalizeSignatureBytes(rawSignature: ByteArray): ByteArray {
        val text = rawSignature.toString(Charsets.UTF_8).trim()

        if (text.startsWith("-----BEGIN") && text.contains("-----END")) {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----BEGIN") && !it.startsWith("-----END") }
                .toList()
            return Base64.getDecoder().decode(lines.joinToString(""))
        }

        if (text.isNotEmpty() && BASE64_TEXT_REGEX.matches(text)) {
            return try {
                Base64.getDecoder().decode(text.replace("\\s".toRegex(), ""))
            } catch (_: IllegalArgumentException) {
                rawSignature
            }
        }

        return rawSignature
    }

    private fun parseChecksumManifest(manifestText: String, expectedAssetName: String): String? {
        val entries = mutableMapOf<String, String>()
        for (line in manifestText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val hashPrefixMatch = HASH_PREFIX_REGEX.find(trimmed)
            if (hashPrefixMatch != null) {
                val hash = hashPrefixMatch.groupValues[1].lowercase()
                val name = hashPrefixMatch.groupValues[2].trim()
                entries[name] = hash
                entries[File(name).name] = hash
                continue
            }

            val opensslMatch = OPENSSL_STYLE_REGEX.find(trimmed)
            if (opensslMatch != null) {
                val name = opensslMatch.groupValues[1].trim()
                val hash = opensslMatch.groupValues[2].lowercase()
                entries[name] = hash
                entries[File(name).name] = hash
            }
        }

        return entries[expectedAssetName] ?: entries[File(expectedAssetName).name]
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Install an update.
     */
    suspend fun installUpdate(downloadPath: String): Boolean {
        val result = UpdateInstaller.installUpdate(downloadPath)

        return when (result) {
            is InstallResult.Success -> {
                println("✅ ${result.message}")
                true
            }
            is InstallResult.RequiresRestart -> {
                println("🔄 ${result.message}")
                serviceScope.launch {
                    delay(1000)
                    quitForUpdate()
                }
                true
            }
            is InstallResult.Error -> {
                println("❌ ${result.message}")
                false
            }
        }
    }

    fun getCurrentPlatform(): String = UpdateInstaller.getCurrentPlatform()

    fun getExpectedAssetName(version: Version): String {
        return when (getCurrentPlatform()) {
            "macOS" -> "BossTerm-${version}.dmg"
            "Windows" -> "BossTerm-${version}.msi"
            "Linux-deb" -> "bossterm_${version}_${getLinuxArch()}.deb"
            "Linux-rpm" -> "bossterm-${version}.${getRpmArch()}.rpm"
            else -> "bossterm-${version}.jar"
        }
    }

    private fun getLinuxArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("amd64") || arch.contains("x86_64") -> "amd64"
            else -> "amd64"
        }
    }

    private fun getRpmArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
            else -> "x86_64"
        }
    }

    /**
     * Quit the application for update installation.
     */
    private fun quitForUpdate() {
        println("Quitting application for update...")
        System.exit(0)
    }

    fun cleanup() {
        serviceScope.cancel()
        apiClient.close()
        downloadClient.close()
    }
}
