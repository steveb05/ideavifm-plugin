package me.steveb05.ideavifm.settings

import junit.framework.TestCase
import me.steveb05.ideavifm.search.DeclarationDepth

class NavigatorSettingsTest : TestCase() {

    fun testDefaults() {
        val state = NavigatorSettings.State()
        assertTrue(state.hideDotFiles)
        assertTrue(state.compactFolders)
        assertFalse(state.showPreview)
        assertFalse("the mouse crossing a pane must not replace what is being read", state.previewOnHover)
        assertEquals(
            "extension functions are found out of the box, class members are not",
            DeclarationDepth.TOP_LEVEL,
            state.declarationDepth,
        )
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
                previewOnHover = true,
                leftPaneChildren = false,
                leftPaneChildFiles = false,
                restoreLastView = true,
                openCreatedFile = false,
                declarationDepth = DeclarationDepth.SYMBOLS,
            ),
        )
        assertTrue(settings.previewOnHover)
        assertEquals(DeclarationDepth.SYMBOLS, settings.declarationDepth)
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
