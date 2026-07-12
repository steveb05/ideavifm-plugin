package me.steveb05.projecttreenavigator

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SubtreeMatchesTest : BasePlatformTestCase() {

    fun testMatchesRollUpIntoAncestors() {
        val ax = myFixture.addFileToProject("a/x.txt", "").virtualFile
        val ay = myFixture.addFileToProject("a/sub/y.txt", "").virtualFile
        val top = myFixture.addFileToProject("top.txt", "").virtualFile
        val entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("a"), "a", true),
            BaseEntry(myFixture.findFileInTempDir("a/sub"), "sub", true, indent = 1),
            BaseEntry(top, "top.txt", false),
        )
        val items = listOf(ay, top, ax)
        assertEquals(listOf(ay, ax), SubtreeMatches.matchesUnder(items, entries[0]) { it })
        assertEquals(listOf(ay), SubtreeMatches.matchesUnder(items, entries[1]) { it })
        assertEquals(listOf(top), SubtreeMatches.matchesUnder(items, entries[2]) { it })
        val counts = SubtreeMatches.countsFor(items, entries) { it }
        assertEquals(2, counts[entries[0]])
        assertEquals(1, counts[entries[1]])
        assertEquals(1, counts[entries[2]])
    }

    fun testItemsOutsideEveryEntryCountNowhere() {
        val inA = myFixture.addFileToProject("a/x.txt", "").virtualFile
        val orphan = myFixture.addFileToProject("c/orphan.txt", "").virtualFile
        val entries = listOf(BaseEntry(myFixture.findFileInTempDir("a"), "a", true))
        assertEquals(listOf(inA), SubtreeMatches.matchesUnder(listOf(orphan, inA), entries[0]) { it })
        assertEquals(mapOf(entries[0] to 1), SubtreeMatches.countsFor(listOf(orphan, inA), entries) { it })
    }
}
