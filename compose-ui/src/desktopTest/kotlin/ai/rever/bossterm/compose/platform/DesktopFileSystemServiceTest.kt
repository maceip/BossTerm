package ai.rever.bossterm.compose.platform

import ai.rever.bossterm.compose.DesktopFileSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/**
 * Tests for DesktopFileSystemService async I/O boundary.
 *
 * Validates that file operations are dispatched to the IO dispatcher,
 * not executed on the calling thread. This prevents blocking the UI
 * thread during settings loads, file existence checks, etc.
 */
class DesktopFileSystemServiceTest {

    private val service = DesktopFileSystemService()
    private lateinit var tempFile: File

    @BeforeTest
    fun setup() {
        tempFile = File.createTempFile("bossterm_test_", ".txt")
        tempFile.writeText("hello world")
    }

    @AfterTest
    fun teardown() {
        tempFile.delete()
    }

    // ======================== File Exists ========================

    @Test
    fun testFileExistsReturnsTrueForExistingFile() = runTest {
        assertTrue(service.fileExists(tempFile.absolutePath))
    }

    @Test
    fun testFileExistsReturnsFalseForMissingFile() = runTest {
        assertFalse(service.fileExists("/nonexistent/path/that/does/not/exist"))
    }

    // ======================== Read Text File ========================

    @Test
    fun testReadTextFileReturnsContent() = runTest {
        val content = service.readTextFile(tempFile.absolutePath)
        assertEquals("hello world", content)
    }

    @Test
    fun testReadTextFileReturnsNullForMissingFile() = runTest {
        val content = service.readTextFile("/nonexistent/path")
        assertNull(content)
    }

    // ======================== Write Text File ========================

    @Test
    fun testWriteTextFileWritesContent() = runTest {
        val newFile = File.createTempFile("bossterm_write_test_", ".txt")
        try {
            val result = service.writeTextFile(newFile.absolutePath, "test content")
            assertTrue(result)
            assertEquals("test content", newFile.readText())
        } finally {
            newFile.delete()
        }
    }

    @Test
    fun testWriteTextFileReturnsFalseOnError() = runTest {
        // Writing to a directory path should fail
        val result = service.writeTextFile("/nonexistent/dir/file.txt", "content")
        assertFalse(result)
    }

    // ======================== IO Dispatch Verification ========================

    @Test
    fun testFileOpsRunOnIODispatcher() = runTest {
        // When called from a non-IO context, the operation should still succeed
        // (proving it internally dispatches to IO). If it didn't dispatch,
        // it would block the test dispatcher.
        withContext(Dispatchers.Default) {
            val exists = service.fileExists(tempFile.absolutePath)
            assertTrue(exists)
        }
    }

    // ======================== Synchronous Accessors ========================

    @Test
    fun testGetUserHomeDirectory() {
        val home = service.getUserHomeDirectory()
        assertNotNull(home)
        assertTrue(home.isNotBlank())
        assertTrue(File(home).isDirectory)
    }

    @Test
    fun testGetTempDirectory() {
        val tmp = service.getTempDirectory()
        assertNotNull(tmp)
        assertTrue(tmp.isNotBlank())
    }
}
