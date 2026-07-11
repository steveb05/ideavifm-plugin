package dev.sb.projecttreenavigator

import junit.framework.TestCase

class PrunedTreeCompactTest : TestCase() {

    fun testCompactMergesSingleDirectoryChains() {
        val nodes = PrunedTreeBuilder.build(
            listOf(PrunedMatch(listOf("a", "b", "c", "file.txt"), "payload", 5)),
        )
        val compacted = PrunedTreeBuilder.compact(nodes)
        assertEquals(1, compacted.size)
        assertEquals("a/b/c", compacted[0].name)
        assertEquals("file.txt", compacted[0].children.single().name)
    }

    fun testCompactKeepsForks() {
        val nodes = PrunedTreeBuilder.build(
            listOf(
                PrunedMatch(listOf("a", "b", "one.txt"), "p1", 1),
                PrunedMatch(listOf("a", "c", "two.txt"), "p2", 2),
            ),
        )
        val compacted = PrunedTreeBuilder.compact(nodes)
        assertEquals("a", compacted[0].name)
        assertEquals(listOf("b", "c"), compacted[0].children.map { it.name })
    }

    fun testCompactStopsAtFileSiblings() {
        val nodes = PrunedTreeBuilder.build(
            listOf(
                PrunedMatch(listOf("a", "b", "one.txt"), "p1", 1),
                PrunedMatch(listOf("a", "keep.txt"), "p2", 2),
            ),
        )
        val compacted = PrunedTreeBuilder.compact(nodes)
        assertEquals("a", compacted[0].name)
        assertEquals(listOf("b", "keep.txt"), compacted[0].children.map { it.name })
    }
}
