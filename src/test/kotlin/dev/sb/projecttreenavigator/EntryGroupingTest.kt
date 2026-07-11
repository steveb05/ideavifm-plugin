package dev.sb.projecttreenavigator

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EntryGroupingTest : BasePlatformTestCase() {

    fun testGroupsByAncestorEntryPreservingOrder() {
        val ax = myFixture.addFileToProject("a/x.txt", "").virtualFile
        val ay = myFixture.addFileToProject("a/sub/y.txt", "").virtualFile
        val top = myFixture.addFileToProject("top.txt", "").virtualFile
        myFixture.addFileToProject("b/z.txt", "")
        val entries = listOf(
            BaseEntry(myFixture.findFileInTempDir("a"), "a", true),
            BaseEntry(myFixture.findFileInTempDir("b"), "b", true),
            BaseEntry(top, "top.txt", false),
        )
        val buckets = EntryGrouping.group(listOf(ay, top, ax), entries) { it }
        assertEquals(entries, buckets.keys.toList())
        assertEquals(listOf(ay, ax), buckets[entries[0]])
        assertEquals(emptyList<VirtualFile>(), buckets[entries[1]])
        assertEquals(listOf(top), buckets[entries[2]])
    }

    fun testDropsItemsOutsideEveryEntry() {
        val inA = myFixture.addFileToProject("a/x.txt", "").virtualFile
        val orphan = myFixture.addFileToProject("c/orphan.txt", "").virtualFile
        val entries = listOf(BaseEntry(myFixture.findFileInTempDir("a"), "a", true))
        val buckets = EntryGrouping.group(listOf(orphan, inA), entries) { it }
        assertEquals(listOf(inA), buckets[entries[0]])
    }
}
