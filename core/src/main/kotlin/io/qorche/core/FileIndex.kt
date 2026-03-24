package io.qorche.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

@Serializable
data class FileIndexEntry(
    val relativePath: String,
    val size: Long,
    val lastModifiedEpochMs: Long,
    val hash: String
)

class FileIndex {

    private val entries = ConcurrentHashMap<String, FileIndexEntry>()
    private val json = Json { prettyPrint = false }

    fun getOrComputeHash(file: Path, relativePath: String): String {
        val size = file.fileSize()
        val mtime = file.getLastModifiedTime().toMillis()

        val cached = entries[relativePath]
        if (cached != null && cached.size == size && cached.lastModifiedEpochMs == mtime) {
            return cached.hash
        }

        val hash = hashFile(file)
        entries[relativePath] = FileIndexEntry(relativePath, size, mtime, hash)
        return hash
    }

    fun allEntries(): Collection<FileIndexEntry> = entries.values

    fun loadFrom(saved: List<FileIndexEntry>) {
        entries.clear()
        for (entry in saved) {
            entries[entry.relativePath] = entry
        }
    }

    fun exportEntries(): List<FileIndexEntry> = entries.values.toList()

    /**
     * Persist the file index to disk for warm cache on next startup.
     */
    fun saveTo(path: Path) {
        path.createParentDirectories()
        val data = json.encodeToString(exportEntries())
        Files.writeString(path, data)
    }

    /**
     * Load a previously persisted file index from disk.
     */
    fun loadFrom(path: Path): Boolean {
        if (!path.exists()) return false
        return try {
            val saved = json.decodeFromString<List<FileIndexEntry>>(path.readText())
            loadFrom(saved)
            true
        } catch (_: Exception) {
            false
        }
    }

    val size: Int get() = entries.size
}
