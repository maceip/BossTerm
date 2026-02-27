package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Resolves and validates file paths for hyperlink detection.
 *
 * Supports:
 * - Absolute Unix paths: /path/to/file
 * - Absolute Windows paths: C:\path\to\file
 * - Home-relative paths: ~/path/to/file
 * - Relative paths: ./path, ../path (requires working directory)
 *
 * Thread-safe: All operations are safe to call from any thread.
 */
object FilePathResolver {
    /**
     * Cache for path existence checks to avoid hitting the filesystem on every render.
     * Keyed by namespace (mount/cwd segment), then absolute path string.
     * Cache entries expire after 5 seconds to catch file changes.
     */
    private val pathCacheByNamespace = ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<Boolean, Long>>>()
    private const val CACHE_TTL_MS = 5000L // 5 seconds
    internal const val MAX_CACHE_SIZE = 1000
    internal const val MAX_NAMESPACES = 64

    /**
     * Background executor for async file existence checks.
     * Single-threaded to limit filesystem I/O pressure from hyperlink detection.
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FilePathResolver-IO").apply { isDaemon = true }
    }

    /**
     * Set of paths currently being checked asynchronously, to avoid duplicate work.
     */
    private val pendingChecks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Windows path pattern (C:\, D:\, etc.)
     */
    private val windowsPathPattern = Regex("""^[A-Za-z]:\\.*""")

    /**
     * Resolve a path string to an absolute File, optionally using a working directory.
     *
     * @param path The path string to resolve
     * @param workingDirectory The current working directory for relative paths (optional)
     * @return Resolved File if path is valid format, null otherwise
     */
    fun resolvePath(path: String, workingDirectory: String?): File? {
        if (path.isBlank()) return null

        val resolved = when {
            // Absolute Unix path
            path.startsWith("/") -> File(path)

            // Absolute Windows path (C:\, D:\, etc.)
            windowsPathPattern.matches(path) -> File(path)

            // Home-relative path (~/...)
            path.startsWith("~/") -> {
                val home = System.getProperty("user.home") ?: return null
                File(home, path.drop(2))
            }

            // Relative paths (./... or ../...)
            path.startsWith("./") || path.startsWith("../") -> {
                val cwd = workingDirectory ?: return null
                try {
                    File(cwd, path).canonicalFile
                } catch (e: Exception) {
                    null
                }
            }

            // Not a recognized path format
            else -> null
        }

        return try {
            resolved?.canonicalFile
        } catch (e: Exception) {
            // Windows throws IOException for invalid pathnames
            null
        }
    }

    /**
     * Check if a resolved path exists, with caching for performance.
     *
     * @param file The file to check
     * @return true if the file exists
     */
    fun exists(file: File): Boolean = exists(file, namespaceFor(file))

    private fun exists(file: File, namespace: String): Boolean {
        val path = file.absolutePath
        val now = System.currentTimeMillis()
        val cache = getOrCreateNamespaceCache(namespace) ?: return false

        // Check cache first
        cache[path]?.let { (exists, timestamp) ->
            if (now - timestamp < CACHE_TTL_MS) {
                return exists
            }
        }

        // Evict old entries if cache is too large
        if (cache.size > MAX_CACHE_SIZE) {
            val expiredThreshold = now - CACHE_TTL_MS
            cache.entries.removeIf { it.value.second < expiredThreshold }
        }

        // Check filesystem and cache result
        val exists = file.exists()
        cache[path] = Pair(exists, now)
        return exists
    }

    /**
     * Resolve path and validate it exists.
     *
     * @param path The path string to resolve
     * @param workingDirectory The current working directory for relative paths
     * @return Resolved File if it exists, null otherwise
     */
    fun resolveAndValidate(path: String, workingDirectory: String?): File? {
        val resolved = resolvePath(path, workingDirectory) ?: return null
        val namespace = workingDirectory?.let { namespaceFor(File(it)) } ?: namespaceFor(resolved)
        return if (exists(resolved, namespace)) resolved else null
    }

    /**
     * Non-blocking variant of [resolveAndValidate] safe for the render path.
     *
     * Returns the resolved file immediately if the cache already holds a fresh result.
     * On a cache miss, schedules a background filesystem check and returns null.
     * The caller (render loop) will pick up the result on the next frame.
     *
     * This ensures the render thread never performs blocking I/O.
     */
    fun resolveAndValidateCached(path: String, workingDirectory: String?): File? {
        val resolved = resolvePath(path, workingDirectory) ?: return null
        val namespace = workingDirectory?.let { namespaceFor(File(it)) } ?: namespaceFor(resolved)
        val absolutePath = resolved.absolutePath
        val now = System.currentTimeMillis()
        val cache = getOrCreateNamespaceCache(namespace) ?: return null

        // Return cached result if fresh
        cache[absolutePath]?.let { (existsResult, timestamp) ->
            if (now - timestamp < CACHE_TTL_MS) {
                return if (existsResult) resolved else null
            }
        }

        // Cache miss — schedule async check if not already pending
        if (pendingChecks.add(absolutePath)) {
            ioExecutor.execute {
                try {
                    val existsResult = resolved.exists()
                    cache[absolutePath] = Pair(existsResult, System.currentTimeMillis())

                    // Evict if over limit
                    if (cache.size > MAX_CACHE_SIZE) {
                        val threshold = System.currentTimeMillis() - CACHE_TTL_MS
                        cache.entries.removeIf { it.value.second < threshold }
                    }
                } finally {
                    pendingChecks.remove(absolutePath)
                }
            }
        }

        // Return null for this frame — hyperlink will appear on next render
        return null
    }

    /**
     * Convert a File to a file:// URL string suitable for opening.
     *
     * @param file The file to convert
     * @return file:// URL string
     */
    fun toFileUrl(file: File): String {
        return file.toURI().toString()
    }

    /**
     * Clear the path existence cache.
     * Call this if you know files have been created/deleted and want immediate refresh.
     */
    fun clearCache() {
        pathCacheByNamespace.clear()
    }

    /**
     * Get or create a namespace cache, with bounds on total namespace count.
     * Returns null if namespace limit exceeded and this namespace isn't cached.
     */
    private fun getOrCreateNamespaceCache(namespace: String): ConcurrentHashMap<String, Pair<Boolean, Long>>? {
        pathCacheByNamespace[namespace]?.let { return it }

        // Evict stale namespaces before adding new one
        if (pathCacheByNamespace.size >= MAX_NAMESPACES) {
            val now = System.currentTimeMillis()
            val toRemove = pathCacheByNamespace.entries
                .filter { (_, cache) ->
                    cache.isEmpty() || cache.values.all { (_, ts) -> now - ts > CACHE_TTL_MS }
                }
                .map { it.key }
            toRemove.forEach { pathCacheByNamespace.remove(it) }

            // Still over limit — refuse to create new namespace
            if (pathCacheByNamespace.size >= MAX_NAMESPACES) return null
        }

        return pathCacheByNamespace.getOrPut(namespace) { ConcurrentHashMap() }
    }

    internal fun namespaceFor(file: File): String {
        val path = file.absolutePath
        return when {
            path.startsWith("//") || path.startsWith("\\\\") -> "unc"
            windowsPathPattern.matches(path) -> "win:${path.substring(0, 1).uppercase()}"
            path.startsWith("/") -> path.substringAfter("/", "").substringBefore("/", missingDelimiterValue = "/")
                .let { segment -> "posix:${if (segment.isBlank()) "/" else segment}" }
            else -> "global"
        }
    }

    /**
     * The current number of namespace caches. Exposed for testing.
     */
    internal fun namespaceCacheCount(): Int = pathCacheByNamespace.size

    /**
     * Check if a path string looks like a Unix absolute path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeUnixPath(text: String): Boolean {
        return text.contains('/') && !text.contains("://")
    }

    /**
     * Check if a path string looks like a home-relative path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeHomePath(text: String): Boolean {
        return text.contains("~/")
    }

    /**
     * Check if a path string looks like a relative path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeRelativePath(text: String): Boolean {
        return text.contains("./")
    }

    /**
     * Check if a path string looks like a Windows path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeWindowsPath(text: String): Boolean {
        return text.contains(":\\")
    }
}
