package io.qorche.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

@Serializable
data class Snapshot(
    val id: String,
    val timestamp: Instant,
    val fileHashes: Map<String, String>,
    val description: String,
    val parentId: String? = null
)

@Serializable
data class SnapshotDiff(
    val added: Set<String>,
    val modified: Set<String>,
    val deleted: Set<String>,
    val beforeId: String,
    val afterId: String
) {
    val totalChanges: Int get() = added.size + modified.size + deleted.size

    fun summary(): String = buildString {
        if (added.isNotEmpty()) append("+${added.size} added")
        if (modified.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append("~${modified.size} modified")
        }
        if (deleted.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append("-${deleted.size} deleted")
        }
        if (isEmpty()) append("no changes")
    }
}

object SnapshotCreator {

    private val IGNORED_PREFIXES = listOf(".git/", ".gradle/", ".idea/", ".qorche/", "build/")

    /**
     * Create a full-repo snapshot — walks the entire directory tree.
     */
    suspend fun create(
        directory: Path,
        description: String,
        parentId: String? = null,
        fileIndex: FileIndex? = null
    ): Snapshot {
        val files = collectFiles(directory)
        val hashes = hashFilesParallel(directory, files, fileIndex)

        return Snapshot(
            id = generateId(),
            timestamp = Clock.System.now(),
            fileHashes = hashes,
            description = description,
            parentId = parentId
        )
    }

    /**
     * Create a scoped snapshot — only hashes files matching the given paths/globs.
     * Paths can be files or directories (all files under that directory are included).
     */
    suspend fun createScoped(
        directory: Path,
        scopePaths: List<String>,
        description: String,
        parentId: String? = null,
        fileIndex: FileIndex? = null
    ): Snapshot {
        val files = mutableListOf<Path>()

        for (scopePath in scopePaths) {
            val resolved = directory.resolve(scopePath)
            if (Files.isDirectory(resolved)) {
                Files.walk(resolved).use { stream ->
                    stream
                        .filter { it.isRegularFile() }
                        .filter { !isIgnored(directory.relativize(it).toString().replace("\\", "/")) }
                        .forEach { files.add(it) }
                }
            } else if (Files.isRegularFile(resolved)) {
                files.add(resolved)
            }
        }

        val hashes = hashFilesParallel(directory, files, fileIndex)

        return Snapshot(
            id = generateId(),
            timestamp = Clock.System.now(),
            fileHashes = hashes,
            description = description,
            parentId = parentId
        )
    }

    fun diff(before: Snapshot, after: Snapshot): SnapshotDiff {
        val added = after.fileHashes.keys - before.fileHashes.keys
        val deleted = before.fileHashes.keys - after.fileHashes.keys
        val common = before.fileHashes.keys.intersect(after.fileHashes.keys)
        val modified = common.filter { before.fileHashes[it] != after.fileHashes[it] }.toSet()

        return SnapshotDiff(
            added = added,
            modified = modified,
            deleted = deleted,
            beforeId = before.id,
            afterId = after.id
        )
    }

    private fun collectFiles(directory: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walk(directory).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { !isIgnored(directory.relativize(it).toString().replace("\\", "/")) }
                .forEach { files.add(it) }
        }
        return files
    }

    private suspend fun hashFilesParallel(
        directory: Path,
        files: List<Path>,
        fileIndex: FileIndex?
    ): Map<String, String> = coroutineScope {
        val batchSize = (files.size / (Runtime.getRuntime().availableProcessors() * 2)).coerceAtLeast(50)

        files.chunked(batchSize).map { batch ->
            async(Dispatchers.IO) {
                batch.map { file ->
                    val relativePath = file.relativeTo(directory).toString().replace("\\", "/")
                    val hash = fileIndex?.getOrComputeHash(file, relativePath) ?: hashFile(file)
                    relativePath to hash
                }
            }
        }.awaitAll().flatten().toMap()
    }

    private fun isIgnored(relativePath: String): Boolean =
        IGNORED_PREFIXES.any { relativePath.startsWith(it) }
}

/**
 * SHA-256 hash of file contents, streaming through MessageDigest.
 * Strips `\r` bytes before hashing for cross-platform line-ending consistency.
 */
fun hashFile(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    Files.newInputStream(file).use { input ->
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            for (i in 0 until bytesRead) {
                val b = buffer[i]
                if (b == '\r'.code.toByte()) continue
                digest.update(b)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun generateId(): String =
    java.util.UUID.randomUUID().toString()
