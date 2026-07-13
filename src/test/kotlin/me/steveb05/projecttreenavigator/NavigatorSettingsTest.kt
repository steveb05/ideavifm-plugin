package me.steveb05.projecttreenavigator

import junit.framework.TestCase

class NavigatorSettingsTest : TestCase() {

    fun testDefaults() {
        val state = NavigatorSettings.State()
        assertTrue(state.hideDotFiles)
        assertTrue(state.compactFolders)
        assertFalse(state.showPreview)
        assertTrue(state.leftPaneChildren)
        assertTrue(state.leftPaneChildFiles)
        assertFalse("the popup opens on the current file unless asked otherwise", state.restoreLastView)
        assertTrue("creating a file follows it into the editor", state.openCreatedFile)
    }

    fun testLoadStateRoundTrip() {
        val settings = NavigatorSettings()
        settings.loadState(
            NavigatorSettings.State(
                hideDotFiles = false,
                compactFolders = false,
                showPreview = true,
                leftPaneChildren = false,
                leftPaneChildFiles = false,
                restoreLastView = true,
                openCreatedFile = false,
            ),
        )
        assertFalse(settings.openCreatedFile)
        assertFalse(settings.hideDotFiles)
        assertFalse(settings.compactFolders)
        assertTrue(settings.showPreview)
        assertFalse(settings.leftPaneChildren)
        assertFalse(settings.leftPaneChildFiles)
        assertTrue(settings.restoreLastView)
        settings.hideDotFiles = true
        assertTrue(settings.state.hideDotFiles)
    }
}
