package me.steveb05.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NavigatorFileActionsTest : BasePlatformTestCase() {

    fun testEveryActionIdResolves() {
        val manager = ActionManager.getInstance()
        val ids = listOf(
            NavigatorFileActions.NEW,
            NavigatorFileActions.NEW_ELEMENT,
            NavigatorFileActions.RENAME,
            NavigatorFileActions.MOVE,
            NavigatorFileActions.DELETE,
            NavigatorFileActions.COPY,
            NavigatorFileActions.CUT,
            NavigatorFileActions.PASTE,
        )
        for (id in ids) assertNotNull(id, manager.getAction(id))
    }

    fun testContextGroupOffersEverySectionSeparated() {
        val children = NavigatorFileActions.contextGroup()
            .getChildren(null)
            .filterNot { it is Separator }
        assertEquals(7, children.size)
        assertEquals(3, NavigatorFileActions.contextGroup().getChildren(null).count { it is Separator })
    }

    fun testUnknownActionIsIgnored() {
        var done = false
        NavigatorFileActions.perform("ProjectTreeNavigator.NoSuchAction", SimpleDataContext.EMPTY_CONTEXT) {
            done = true
        }
        assertFalse(done)
    }
}
