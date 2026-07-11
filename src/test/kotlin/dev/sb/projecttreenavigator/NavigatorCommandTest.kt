package dev.sb.projecttreenavigator

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke

class NavigatorCommandTest : BasePlatformTestCase() {

    fun testDefaultShortcutUsedWhenKeymapHasNone() {
        val set = NavigatorCommand.LEFT_UP.shortcutSet()
        assertEquals(
            CustomShortcutSet.fromString("alt K").shortcuts.toList(),
            set.shortcuts.toList(),
        )
    }

    fun testKeymapAssignmentWins() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke("alt U"), null)
        keymap.addShortcut(NavigatorCommand.LEFT_UP.actionId, shortcut)
        try {
            assertEquals(listOf(shortcut), NavigatorCommand.LEFT_UP.shortcutSet().shortcuts.toList())
        } finally {
            keymap.removeShortcut(NavigatorCommand.LEFT_UP.actionId, shortcut)
        }
    }
}
