package io.qorche.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port for resolving file-level content conflicts between concurrent writers.
 *
 * When two tasks in a parallel group modify the same file, the orchestrator can
 * attempt an automatic merge instead of discarding the loser's changes outright.
 * Implementations range from pure-algorithmic (three-way text merge) to AI-assisted
 * (LLM-based conflict resolution via adapters in agent/).
 *
 * The resolver receives the common ancestor content (base), both divergent versions,
 * and the file path (for context — some resolvers may use the file type to choose
 * merge strategies). It returns either a successfully merged result or an unresolved
 * marker indicating manual intervention is needed.
 */
interface ConflictResolver {

    /**
     * Attempt to merge two divergent versions of a file against a common base.
     *
     * @param base The file content at the common ancestor (before either task ran).
     * @param versionA Content from the winning task (earlier in group order).
     * @param versionB Content from the losing task (later in group order).
     * @param filePath Relative path of the conflicting file (for context/heuristics).
     * @return [ResolveResult.Merged] with clean content, or [ResolveResult.Conflicted]
     *   with conflict markers embedded in the content.
     */
    fun resolve(
        base: String,
        versionA: String,
        versionB: String,
        filePath: String
    ): ResolveResult
}

/**
 * Result of a conflict resolution attempt.
 */
@Serializable
sealed class ResolveResult {

    /**
     * Both sides' changes were merged cleanly — no human intervention needed.
     * [content] is the final merged file content, ready to write to disk.
     */
    @Serializable
    @SerialName("merged")
    data class Merged(val content: String) : ResolveResult()

    /**
     * At least one hunk could not be merged automatically.
     * [content] contains the partially merged file with git-style conflict markers
     * (`<<<<<<<`, `=======`, `>>>>>>>`) around unresolvable regions.
     * [conflictCount] is the number of conflict regions in the output.
     */
    @Serializable
    @SerialName("conflicted")
    data class Conflicted(
        val content: String,
        val conflictCount: Int
    ) : ResolveResult()
}

/**
 * Three-way text merge using a line-based diff algorithm.
 *
 * Compares [base] → [versionA] and [base] → [versionB] independently,
 * then combines non-overlapping edits into a single result. When both sides
 * modify the same region, git-style conflict markers are inserted.
 *
 * This is a pure algorithmic resolver with no external dependencies — it runs
 * entirely in core/ and works for any text file. Binary files or files where
 * line-based merging is inappropriate should use a different resolver.
 *
 * Algorithm:
 * 1. Split all three versions into lines
 * 2. Compute longest common subsequence (LCS) diffs: base→A and base→B
 * 3. Walk both diffs simultaneously, applying non-overlapping changes
 * 4. Mark overlapping changes with conflict markers
 */
class TextualMergeResolver : ConflictResolver {

    override fun resolve(
        base: String,
        versionA: String,
        versionB: String,
        filePath: String
    ): ResolveResult {
        // Fast paths
        if (versionA == versionB) return ResolveResult.Merged(versionA)
        if (base == versionA) return ResolveResult.Merged(versionB)
        if (base == versionB) return ResolveResult.Merged(versionA)

        val baseLines = base.lines()
        val linesA = versionA.lines()
        val linesB = versionB.lines()

        val hunksA = computeHunks(baseLines, linesA)
        val hunksB = computeHunks(baseLines, linesB)

        return mergeHunks(baseLines, hunksA, hunksB, linesA, linesB)
    }

    /**
     * A hunk represents a contiguous region of change between the base and a modified version.
     *
     * @property baseStart Start line in the base (inclusive, 0-indexed).
     * @property baseEnd End line in the base (exclusive).
     * @property modStart Start line in the modified version (inclusive).
     * @property modEnd End line in the modified version (exclusive).
     */
    internal data class Hunk(
        val baseStart: Int,
        val baseEnd: Int,
        val modStart: Int,
        val modEnd: Int
    )

    /**
     * Compute the list of change hunks between [base] and [modified] using LCS.
     */
    internal fun computeHunks(base: List<String>, modified: List<String>): List<Hunk> {
        val lcs = longestCommonSubsequence(base, modified)
        val hunks = mutableListOf<Hunk>()

        var baseIdx = 0
        var modIdx = 0

        for ((lcsBaseIdx, lcsModIdx) in lcs) {
            if (baseIdx < lcsBaseIdx || modIdx < lcsModIdx) {
                hunks.add(Hunk(baseIdx, lcsBaseIdx, modIdx, lcsModIdx))
            }
            baseIdx = lcsBaseIdx + 1
            modIdx = lcsModIdx + 1
        }

        // Trailing changes after last LCS match
        if (baseIdx < base.size || modIdx < modified.size) {
            hunks.add(Hunk(baseIdx, base.size, modIdx, modified.size))
        }

        return hunks
    }

    /**
     * Compute LCS as a list of (baseIndex, modifiedIndex) pairs.
     * Uses the standard DP approach — O(n×m) time and space.
     * Sufficient for typical source files (< 10k lines).
     */
    internal fun longestCommonSubsequence(
        a: List<String>,
        b: List<String>
    ): List<Pair<Int, Int>> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to find actual pairs
        val result = mutableListOf<Pair<Int, Int>>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    result.add((i - 1) to (j - 1))
                    i--
                    j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }

        return result.reversed()
    }

    /**
     * Mutable state carried through the merge walk.
     */
    private data class MergeState(
        val output: MutableList<String> = mutableListOf(),
        var conflictCount: Int = 0,
        var basePos: Int = 0,
        var idxA: Int = 0,
        var idxB: Int = 0
    )

    /**
     * Merge two sets of hunks against the base, producing either a clean merge
     * or a conflicted result with markers.
     */
    private fun mergeHunks(
        baseLines: List<String>,
        hunksA: List<Hunk>,
        hunksB: List<Hunk>,
        linesA: List<String>,
        linesB: List<String>
    ): ResolveResult {
        val state = MergeState()

        while (state.idxA < hunksA.size || state.idxB < hunksB.size) {
            val hunkA = hunksA.getOrNull(state.idxA)
            val hunkB = hunksB.getOrNull(state.idxB)

            when {
                hunkA != null && hunkB == null -> {
                    applySingleHunk(state, baseLines, hunkA, linesA)
                    state.idxA++
                }
                hunkA == null && hunkB != null -> {
                    applySingleHunk(state, baseLines, hunkB, linesB)
                    state.idxB++
                }
                hunkA != null && hunkB != null -> mergeOverlappingHunks(state, baseLines, hunkA, hunkB, linesA, linesB)
                else -> break
            }
        }

        copyBaseUntil(baseLines, state.basePos, baseLines.size, state.output)
        val content = state.output.joinToString("\n")
        return if (state.conflictCount == 0) ResolveResult.Merged(content)
        else ResolveResult.Conflicted(content, state.conflictCount)
    }

    /** Apply a hunk from one side when the other has no more hunks. */
    private fun applySingleHunk(state: MergeState, baseLines: List<String>, hunk: Hunk, lines: List<String>) {
        copyBaseUntil(baseLines, state.basePos, hunk.baseStart, state.output)
        state.output.addAll(lines.subList(hunk.modStart, hunk.modEnd))
        state.basePos = hunk.baseEnd
    }

    /** Handle two hunks that may or may not overlap. */
    private fun mergeOverlappingHunks(
        state: MergeState,
        baseLines: List<String>,
        hunkA: Hunk,
        hunkB: Hunk,
        linesA: List<String>,
        linesB: List<String>
    ) {
        when {
            hunkA.baseEnd <= hunkB.baseStart -> {
                copyBaseUntil(baseLines, state.basePos, hunkA.baseStart, state.output)
                state.output.addAll(linesA.subList(hunkA.modStart, hunkA.modEnd))
                state.basePos = hunkA.baseEnd
                state.idxA++
            }
            hunkB.baseEnd <= hunkA.baseStart -> {
                copyBaseUntil(baseLines, state.basePos, hunkB.baseStart, state.output)
                state.output.addAll(linesB.subList(hunkB.modStart, hunkB.modEnd))
                state.basePos = hunkB.baseEnd
                state.idxB++
            }
            else -> {
                val aContent = linesA.subList(hunkA.modStart, hunkA.modEnd)
                val bContent = linesB.subList(hunkB.modStart, hunkB.modEnd)
                copyBaseUntil(baseLines, state.basePos, minOf(hunkA.baseStart, hunkB.baseStart), state.output)

                if (aContent == bContent) {
                    state.output.addAll(aContent)
                } else {
                    state.conflictCount++
                    state.output.add(CONFLICT_START)
                    state.output.addAll(aContent)
                    state.output.add(CONFLICT_SEPARATOR)
                    state.output.addAll(bContent)
                    state.output.add(CONFLICT_END)
                }
                state.basePos = maxOf(hunkA.baseEnd, hunkB.baseEnd)
                state.idxA++
                state.idxB++
            }
        }
    }

    private fun copyBaseUntil(baseLines: List<String>, from: Int, until: Int, output: MutableList<String>) {
        for (i in from until until) {
            output.add(baseLines[i])
        }
    }

    companion object {
        /** Git-style conflict markers. */
        const val CONFLICT_START = "<<<<<<< version-a"
        const val CONFLICT_SEPARATOR = "======="
        const val CONFLICT_END = ">>>>>>> version-b"
    }
}
