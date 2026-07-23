package me.steveb05.ideavifm

import com.intellij.openapi.vcs.FileStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThreeState

class VcsStatusColorTest : BasePlatformTestCase() {

    fun testCleanEntryHasNoColor() {
        assertNull(VcsStatusColor.colorFor(FileStatus.NOT_CHANGED, containsChanges = false))
    }

    fun testChangedEntryKeepsItsOwnStatusColor() {
        assertEquals(FileStatus.MODIFIED.color, VcsStatusColor.colorFor(FileStatus.MODIFIED, false))
        assertEquals(FileStatus.UNKNOWN.color, VcsStatusColor.colorFor(FileStatus.UNKNOWN, false))
    }

    fun testCleanFolderHoldingChangesBorrowsTheModifiedColor() {
        assertEquals(
            FileStatus.MODIFIED.color,
            VcsStatusColor.colorFor(FileStatus.NOT_CHANGED, containsChanges = true),
        )
    }

    fun testChangesDeeperDownStillCountAsChangesUnderAFolder() {
        assertTrue("the immediate parent of a changed file", VcsStatusColor.containsChanges(ThreeState.YES))
        assertTrue(
            "every folder above it, which is what src and the module root hit",
            VcsStatusColor.containsChanges(ThreeState.UNSURE),
        )
        assertFalse(VcsStatusColor.containsChanges(ThreeState.NO))
    }
}
