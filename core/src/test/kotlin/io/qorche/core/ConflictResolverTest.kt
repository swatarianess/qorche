package io.qorche.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConflictResolverTest {

    private val resolver = TextualMergeResolver()

    @Test
    fun `identical versions return merged`() {
        val base = "line1\nline2\nline3"
        val result = resolver.resolve(base, "same\ncontent", "same\ncontent", "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("same\ncontent", result.content)
    }

    @Test
    fun `only A changed returns A`() {
        val base = "line1\nline2"
        val a = "line1\nmodified"
        val result = resolver.resolve(base, a, base, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals(a, result.content)
    }

    @Test
    fun `only B changed returns B`() {
        val base = "line1\nline2"
        val b = "line1\nmodified"
        val result = resolver.resolve(base, base, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals(b, result.content)
    }

    @Test
    fun `non-overlapping changes merge cleanly`() {
        val base = "line1\nline2\nline3\nline4\nline5"
        val a = "MODIFIED1\nline2\nline3\nline4\nline5"  // Changed line 1
        val b = "line1\nline2\nline3\nline4\nMODIFIED5"  // Changed line 5

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("MODIFIED1\nline2\nline3\nline4\nMODIFIED5", result.content)
    }

    @Test
    fun `overlapping changes produce conflict markers`() {
        val base = "line1\nline2\nline3"
        val a = "line1\nchanged-by-A\nline3"
        val b = "line1\nchanged-by-B\nline3"

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Conflicted>(result)
        assertEquals(1, result.conflictCount)
        assert(result.content.contains(TextualMergeResolver.CONFLICT_START))
        assert(result.content.contains("changed-by-A"))
        assert(result.content.contains(TextualMergeResolver.CONFLICT_SEPARATOR))
        assert(result.content.contains("changed-by-B"))
        assert(result.content.contains(TextualMergeResolver.CONFLICT_END))
    }

    @Test
    fun `overlapping identical changes merge cleanly`() {
        val base = "line1\nline2\nline3"
        val a = "line1\nsame-change\nline3"
        val b = "line1\nsame-change\nline3"

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("line1\nsame-change\nline3", result.content)
    }

    @Test
    fun `A adds lines at beginning, B adds at end`() {
        val base = "middle"
        val a = "top\nmiddle"
        val b = "middle\nbottom"

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("top\nmiddle\nbottom", result.content)
    }

    @Test
    fun `A deletes a line, B modifies different line`() {
        val base = "line1\nline2\nline3\nline4"
        val a = "line1\nline3\nline4"           // Deleted line2
        val b = "line1\nline2\nline3\nMODIFIED" // Modified line4

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("line1\nline3\nMODIFIED", result.content)
    }

    @Test
    fun `empty base with both adding content`() {
        val base = ""
        val a = "added-by-A"
        val b = "added-by-B"

        val result = resolver.resolve(base, a, b, "test.txt")
        // Both are adding to the same empty region — should conflict
        assertIs<ResolveResult.Conflicted>(result)
        assertEquals(1, result.conflictCount)
    }

    @Test
    fun `multiple non-overlapping changes merge cleanly`() {
        val base = "a\nb\nc\nd\ne\nf\ng\nh"
        val a = "A\nb\nc\nd\ne\nf\ng\nh"     // Changed line 1
        val b = "a\nb\nc\nD\ne\nf\ng\nH"     // Changed lines 4 and 8

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Merged>(result)
        assertEquals("A\nb\nc\nD\ne\nf\ng\nH", result.content)
    }

    @Test
    fun `multiple conflicts produce correct count`() {
        val base = "a\nb\nc"
        val a = "X\nb\nY"
        val b = "P\nb\nQ"

        val result = resolver.resolve(base, a, b, "test.txt")
        assertIs<ResolveResult.Conflicted>(result)
        assertEquals(2, result.conflictCount)
    }

    @Test
    fun `resolve result is serializable`() {
        val merged = ResolveResult.Merged("content")
        val json = kotlinx.serialization.json.Json.encodeToString(ResolveResult.serializer(), merged)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(ResolveResult.serializer(), json)
        assertEquals(merged, decoded)

        val conflicted = ResolveResult.Conflicted("content with markers", 2)
        val json2 = kotlinx.serialization.json.Json.encodeToString(ResolveResult.serializer(), conflicted)
        val decoded2 = kotlinx.serialization.json.Json.decodeFromString(ResolveResult.serializer(), json2)
        assertEquals(conflicted, decoded2)
    }

    // --- LCS algorithm tests ---

    @Test
    fun `LCS of identical lists returns full indices`() {
        val lines = listOf("a", "b", "c")
        val lcs = resolver.longestCommonSubsequence(lines, lines)
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2), lcs)
    }

    @Test
    fun `LCS of disjoint lists is empty`() {
        val a = listOf("a", "b")
        val b = listOf("x", "y")
        val lcs = resolver.longestCommonSubsequence(a, b)
        assertEquals(emptyList(), lcs)
    }

    @Test
    fun `LCS finds common subsequence`() {
        val a = listOf("a", "b", "c", "d")
        val b = listOf("a", "x", "c", "d")
        val lcs = resolver.longestCommonSubsequence(a, b)
        // Should find a, c, d as common
        assertEquals(3, lcs.size)
        assertEquals(0 to 0, lcs[0])  // "a"
        assertEquals(2 to 2, lcs[1])  // "c"
        assertEquals(3 to 3, lcs[2])  // "d"
    }

    // --- Hunk computation tests ---

    @Test
    fun `hunks detect single modification`() {
        val base = listOf("a", "b", "c")
        val modified = listOf("a", "X", "c")
        val hunks = resolver.computeHunks(base, modified)
        assertEquals(1, hunks.size)
        assertEquals(TextualMergeResolver.Hunk(1, 2, 1, 2), hunks[0])
    }

    @Test
    fun `hunks detect addition`() {
        val base = listOf("a", "c")
        val modified = listOf("a", "b", "c")
        val hunks = resolver.computeHunks(base, modified)
        assertEquals(1, hunks.size)
        assertEquals(TextualMergeResolver.Hunk(1, 1, 1, 2), hunks[0])
    }

    @Test
    fun `hunks detect deletion`() {
        val base = listOf("a", "b", "c")
        val modified = listOf("a", "c")
        val hunks = resolver.computeHunks(base, modified)
        assertEquals(1, hunks.size)
        assertEquals(TextualMergeResolver.Hunk(1, 2, 1, 1), hunks[0])
    }
}
