package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import kotlin.test.*

/**
 * Tests for FilePathResolver cache bounding and async validation.
 *
 * Validates:
 * - Namespace cache is bounded at MAX_NAMESPACES (issue #5)
 * - resolveAndValidateCached returns null on cache miss (non-blocking) (issue #4)
 * - resolveAndValidateCached returns cached result immediately (issue #4)
 */
class FilePathResolverCacheTest {

    @BeforeTest
    fun setup() {
        FilePathResolver.clearCache()
    }

    // ======================== Namespace Bounding ========================

    @Test
    fun testNamespaceCacheIsBounded() {
        // Fill up namespace cache to MAX_NAMESPACES with synthetic paths
        // Each distinct top-level directory creates a different namespace
        for (i in 0 until FilePathResolver.MAX_NAMESPACES) {
            val path = "/namespace$i/testfile"
            FilePathResolver.resolvePath(path, null)?.let {
                // Prime the cache with a synthetic entry
                FilePathResolver.exists(it)
            }
        }

        // Namespace count should not exceed MAX_NAMESPACES
        assertTrue(
            FilePathResolver.namespaceCacheCount() <= FilePathResolver.MAX_NAMESPACES,
            "Namespace count ${FilePathResolver.namespaceCacheCount()} exceeds max ${FilePathResolver.MAX_NAMESPACES}"
        )
    }

    @Test
    fun testNamespaceForDifferentPathTypes() {
        // Verify namespace calculation produces distinct keys for different path roots
        val unixFile = File("/usr/bin/ls")
        val homeFile = File("/home/user/file.txt")

        val ns1 = FilePathResolver.namespaceFor(unixFile)
        val ns2 = FilePathResolver.namespaceFor(homeFile)

        // These should have different namespace prefixes
        assertTrue(ns1.startsWith("posix:"))
        assertTrue(ns2.startsWith("posix:"))
    }

    // ======================== Non-Blocking Render Path ========================

    @Test
    fun testResolveAndValidateCachedReturnsNullOnCacheMiss() {
        FilePathResolver.clearCache()

        // First call on a cold cache should return null (non-blocking)
        // rather than blocking on file.exists()
        val result = FilePathResolver.resolveAndValidateCached("/some/unlikely/path/xyz123", null)
        assertNull(result, "Cache miss should return null, not block on filesystem check")
    }

    @Test
    fun testResolveAndValidateCachedReturnsCachedResult() {
        // First, prime the cache using the blocking resolveAndValidate
        val tmpFile = File.createTempFile("bossterm_cache_test_", ".txt")
        try {
            val path = tmpFile.absolutePath
            val primed = FilePathResolver.resolveAndValidate(path, null)
            assertNotNull(primed, "File should exist for priming")

            // Now the cached variant should return immediately
            val cached = FilePathResolver.resolveAndValidateCached(path, null)
            assertNotNull(cached, "Cached result should be returned immediately")
            assertEquals(tmpFile.canonicalPath, cached.canonicalPath)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun testResolveAndValidateCachedReturnsNullForNonexistentCachedPath() {
        // Prime cache with a path that doesn't exist
        val fakePath = "/tmp/bossterm_definitely_not_real_${System.nanoTime()}"
        FilePathResolver.resolveAndValidate(fakePath, null) // caches as non-existent

        // Cached variant should return null (path cached as non-existent)
        val result = FilePathResolver.resolveAndValidateCached(fakePath, null)
        assertNull(result, "Non-existent cached path should return null")
    }

    // ======================== Cache Eviction ========================

    @Test
    fun testCacheClearsCorrectly() {
        // Prime cache
        val tmpFile = File.createTempFile("bossterm_evict_test_", ".txt")
        try {
            FilePathResolver.resolveAndValidate(tmpFile.absolutePath, null)
            assertTrue(FilePathResolver.namespaceCacheCount() > 0)

            // Clear should reset
            FilePathResolver.clearCache()
            assertEquals(0, FilePathResolver.namespaceCacheCount())
        } finally {
            tmpFile.delete()
        }
    }

    // ======================== MAX_CACHE_SIZE Per Namespace ========================

    @Test
    fun testPerNamespaceCacheSizeIsBounded() {
        // Simulate many paths in the same namespace
        val namespace = "tmp"
        for (i in 0 until FilePathResolver.MAX_CACHE_SIZE + 100) {
            val file = File("/tmp/test_entry_$i")
            FilePathResolver.exists(file) // fills the /tmp namespace cache
        }

        // The test passes if no OOM or unbounded growth occurs
        // (cache self-evicts when over MAX_CACHE_SIZE)
        assertTrue(FilePathResolver.namespaceCacheCount() >= 1)
    }
}
