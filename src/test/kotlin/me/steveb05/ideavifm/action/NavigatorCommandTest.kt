package me.steveb05.ideavifm.action

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke

class NavigatorCommandTest : BasePlatformTestCase() {

    fun testDefaultsComeFromKeymap() {
        assertEquals(
            CustomShortcutSet.fromString("alt K").shortcuts.toList(),
            NavigatorCommand.LEFT_UP.shortcutSet().shortcuts.toList(),
        )
    }

    fun testKeymapAssignmentAddsShortcut() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke("alt U"), null)
        keymap.addShortcut(NavigatorCommand.LEFT_UP.actionId, shortcut)
        try {
            assertTrue(NavigatorCommand.LEFT_UP.shortcutSet().shortcuts.toList().contains(shortcut))
        } finally {
            keymap.removeShortcut(NavigatorCommand.LEFT_UP.actionId, shortcut)
        }
    }

    fun testEveryCommandHasDefaultShortcut() {
        for (command in NavigatorCommand.entries) {
            assertTrue(command.actionId, command.shortcutSet().shortcuts.isNotEmpty())
        }
    }

    fun testRemovedDefaultIsRespected() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val default = KeyboardShortcut(KeyStroke.getKeyStroke("alt K"), null)
        keymap.removeShortcut(NavigatorCommand.LEFT_UP.actionId, default)
        try {
            assertTrue(NavigatorCommand.LEFT_UP.shortcutSet().shortcuts.isEmpty())
        } finally {
            keymap.addShortcut(NavigatorCommand.LEFT_UP.actionId, default)
        }
    }
}
