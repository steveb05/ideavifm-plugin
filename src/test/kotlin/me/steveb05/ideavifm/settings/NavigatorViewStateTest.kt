package me.steveb05.ideavifm.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NavigatorViewStateTest : BasePlatformTestCase() {

    fun testTheViewComesBackAsItWasLeft() {
        val file = myFixture.addFileToProject("engine/src/Region.kt", "").virtualFile
        val zoomed = myFixture.findFileInTempDir("engine/src")
        val entry = myFixture.findFileInTempDir("engine")
        val state = NavigatorViewState.getInstance(project)

        state.save(scope = "Module", zoom = listOf(zoomed), entry = entry, file = file)

        assertEquals("Module", state.scope())
        assertEquals(listOf(zoomed), state.zoom())
        assertEquals(entry, state.entry())
        assertEquals(file, state.file())
    }

    fun testAnEmptyViewRestoresToNothing() {
        val state = NavigatorViewState.getInstance(project)
        state.save(scope = "", zoom = emptyList(), entry = null, file = null)
        assertEquals("", state.scope())
        assertTrue(state.zoom().isEmpty())
        assertNull(state.entry())
        assertNull(state.file())
    }

    fun testEntriesThatNoLongerExistAreDropped() {
        val state = NavigatorViewState.getInstance(project)
        state.loadState(
            NavigatorViewState.State(
                scope = "Project",
                zoom = mutableListOf("temp:///gone/for/good"),
                entry = "temp:///gone/for/good",
                file = "temp:///gone/for/good/x.kt",
            ),
        )
        assertTrue("a folder deleted since last time must not be restored", state.zoom().isEmpty())
        assertNull(state.entry())
        assertNull(state.file())
    }
}
