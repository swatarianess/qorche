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

/** Cached metadata and hash for a single tracked file. */
@Serializable
data class FileIndexEntry(
    val relativePath: String,
    val size: Long,
    val lastModifiedEpochMs: Long,
    val lastModifiedNanos: Long = 0,
    val hash: String
)

/** Concurrent cache mapping relative paths to file metadata and content hashes. */
class FileIndex {

    private val entries = ConcurrentHashMap<String, FileIndexEntry>()
    private val json = Json { prettyPrint = false }

    /**
     * Returns the cached hash if size and mtime match, otherwise recomputes and caches it.
     * Uses nanosecond precision where the filesystem supports it.
     */
    fun getOrComputeHash(file: Path, relativePath: String): String {
        val size = file.fileSize()
        val fileTime = file.getLastModifiedTime()
        val mtimeMs = fileTime.toMillis()
        val mtimeNanos = fileTime.to(java.util.concurrent.TimeUnit.NANOSECONDS)

        val cached = entries[relativePath]
        if (cached != null && cached.size == size
            && cached.lastModifiedEpochMs == mtimeMs
            && cached.lastModifiedNanos == mtimeNanos) {
            return cached.hash
        }

        val hash = hashFile(file)
        entries[relativePath] = FileIndexEntry(relativePath, size, mtimeMs, mtimeNanos, hash)
        return hash
    }

    /** Clears all cached entries, forcing rehash on next access. */
    fun clear() {
        entries.clear()
    }

    /** Removes the cached entry for the given relative path. */
    fun invalidate(relativePath: String) {
        entries.remove(relativePath)
    }

    /** Removes cached entries for all given relative paths. */
    fun invalidateAll(relativePaths: Collection<String>) {
        for (path in relativePaths) entries.remove(path)
    }

    /** Returns all currently cached index entries. */
    fun allEntries(): Collection<FileIndexEntry> = entries.values

    /** Replaces the entire index with the given entries. */
    fun loadFrom(saved: List<FileIndexEntry>) {
        entries.clear()
        for (entry in saved) {
            entries[entry.relativePath] = entry
        }
    }

    /** Returns a snapshot copy of all cached entries as a list. */
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

    /** The number of files currently in the index. */
    val size: Int get() = entries.size
}
