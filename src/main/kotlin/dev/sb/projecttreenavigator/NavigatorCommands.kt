package dev.sb.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import javax.swing.JComponent

enum class NavigatorCommand(val actionId: String, val defaultShortcut: String) {
    LEFT_UP("ProjectTreeNavigator.LeftUp", "alt K"),
    LEFT_DOWN("ProjectTreeNavigator.LeftDown", "alt J"),
    RIGHT_UP("ProjectTreeNavigator.RightUp", "control K"),
    RIGHT_DOWN("ProjectTreeNavigator.RightDown", "control J"),
    PANE_LEFT("ProjectTreeNavigator.PaneLeft", "control H"),
    PANE_RIGHT("ProjectTreeNavigator.PaneRight", "control L"),
    ZOOM_IN("ProjectTreeNavigator.ZoomIn", "control ENTER"),
    ZOOM_OUT("ProjectTreeNavigator.ZoomOut", "BACK_SPACE"),
    TOGGLE_PREVIEW("ProjectTreeNavigator.TogglePreview", "alt P"),
    TOGGLE_DOT_FILES("ProjectTreeNavigator.ToggleDotFiles", "control PERIOD"),
    ;

    fun shortcutSet(): CustomShortcutSet {
        val assigned = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
        if (assigned.isNotEmpty()) return CustomShortcutSet(*assigned)
        return CustomShortcutSet.fromString(defaultShortcut)
    }
}

class NavigatorCommandStubAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class NavigatorCommands(
    private val panel: JComponent,
    private val popup: JBPopup,
) {

    fun bind(command: NavigatorCommand, isEnabled: () -> Boolean = { true }, perform: () -> Unit) {
        register(command.shortcutSet(), isEnabled, perform)
    }

    fun bindFixed(shortcut: String, isEnabled: () -> Boolean = { true }, perform: () -> Unit) {
        register(CustomShortcutSet.fromString(shortcut), isEnabled, perform)
    }

    private fun register(shortcuts: CustomShortcutSet, isEnabled: () -> Boolean, perform: () -> Unit) {
        val action = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isEnabled()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        action.registerCustomShortcutSet(shortcuts, panel, popup)
    }
}
