package dev.sb.projecttreenavigator

import junit.framework.TestCase

class NavigatorSettingsTest : TestCase() {

    fun testDefaults() {
        val state = NavigatorSettings.State()
        assertTrue(state.hideDotFiles)
        assertTrue(state.compactFolders)
        assertFalse(state.showPreview)
    }

    fun testLoadStateRoundTrip() {
        val settings = NavigatorSettings()
        settings.loadState(NavigatorSettings.State(hideDotFiles = false, compactFolders = false, showPreview = true))
        assertFalse(settings.hideDotFiles)
        assertFalse(settings.compactFolders)
        assertTrue(settings.showPreview)
        settings.hideDotFiles = true
        assertTrue(settings.state.hideDotFiles)
    }
}
