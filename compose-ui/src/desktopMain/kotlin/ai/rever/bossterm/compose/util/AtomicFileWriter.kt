package ai.rever.bossterm.compose.util

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.PosixFilePermission

/**
 * Utility for atomic text writes with owner-only permissions when supported.
 */
object AtomicFileWriter {
    private val ownerOnlyPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    )

    fun writeTextAtomic(
        file: File,
        content: String,
        charset: Charset = StandardCharsets.UTF_8,
        backupSuffix: String? = null
    ) {
        val targetPath = file.toPath()
        val parentDir = targetPath.parent ?: throw IOException("Cannot write file without parent: ${file.absolutePath}")
        Files.createDirectories(parentDir)

        val tempFile = Files.createTempFile(parentDir, "${file.name}.", ".tmp")
        try {
            FileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { channel ->
                val bytes = content.toByteArray(charset)
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }

            applyOwnerOnlyPermissions(tempFile)

            if (backupSuffix != null && Files.exists(targetPath)) {
                val backupPath = parentDir.resolve(file.name + backupSuffix)
                Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                applyOwnerOnlyPermissions(backupPath)
            }

            moveAtomic(tempFile, targetPath)
            applyOwnerOnlyPermissions(targetPath)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun moveAtomic(source: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun applyOwnerOnlyPermissions(path: java.nio.file.Path) {
        try {
            Files.setPosixFilePermissions(path, ownerOnlyPermissions)
        } catch (_: UnsupportedOperationException) {
            // Ignore on platforms/filesystems that don't support POSIX permissions.
        } catch (_: Exception) {
            // Best-effort hardening only; don't block persistence on permission adjustment errors.
        }
    }
}
