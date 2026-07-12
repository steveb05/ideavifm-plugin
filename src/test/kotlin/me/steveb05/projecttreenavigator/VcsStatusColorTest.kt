package me.steveb05.projecttreenavigator

import com.intellij.openapi.vcs.FileStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class VcsStatusColorTest : BasePlatformTestCase() {

    fun testCleanEntryHasNoColor() {
        assertNull(VcsStatusColor.colorOf(FileStatus.NOT_CHANGED))
    }

    fun testDirectoryHoldingChangesIsColoredHowEverDeepTheyAre() {
        assertNotNull(
            "a folder whose direct child changed must be tinted",
            VcsStatusColor.colorOf(FileStatus.NOT_CHANGED_IMMEDIATE),
        )
        assertNotNull(
            "a folder whose changes sit deeper must be tinted too, this is what src and the module roots hit",
            VcsStatusColor.colorOf(FileStatus.NOT_CHANGED_RECURSIVE),
        )
    }

    fun testChangedEntryKeepsItsOwnStatusColor() {
        assertEquals(FileStatus.MODIFIED.color, VcsStatusColor.colorOf(FileStatus.MODIFIED))
        assertEquals(FileStatus.UNKNOWN.color, VcsStatusColor.colorOf(FileStatus.UNKNOWN))
    }
}
