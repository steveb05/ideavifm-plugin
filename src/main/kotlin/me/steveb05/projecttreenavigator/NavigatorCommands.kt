package me.steveb05.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import javax.swing.JComponent

enum class NavigatorCommand(val actionId: String) {
    LEFT_UP("ProjectTreeNavigator.LeftUp"),
    LEFT_DOWN("ProjectTreeNavigator.LeftDown"),
    RIGHT_UP("ProjectTreeNavigator.RightUp"),
    RIGHT_DOWN("ProjectTreeNavigator.RightDown"),
    PANE_LEFT("ProjectTreeNavigator.PaneLeft"),
    PANE_RIGHT("ProjectTreeNavigator.PaneRight"),
    ZOOM_IN("ProjectTreeNavigator.ZoomIn"),
    ZOOM_OUT("ProjectTreeNavigator.ZoomOut"),
    TOGGLE_PREVIEW("ProjectTreeNavigator.TogglePreview"),
    TOGGLE_DOT_FILES("ProjectTreeNavigator.ToggleDotFiles"),
    TOGGLE_CHANGED("ProjectTreeNavigator.ToggleChangedOnly"),
    TOGGLE_MARK("ProjectTreeNavigator.ToggleMark"),
    FOCUS_SEARCH("ProjectTreeNavigator.FocusSearch"),
    RESET_TREE("ProjectTreeNavigator.ResetTree"),
    NEW_ELEMENT(NavigatorFileActions.NEW_ELEMENT),
    PREVIEW_LINE_DOWN("ProjectTreeNavigator.PreviewLineDown"),
    PREVIEW_LINE_UP("ProjectTreeNavigator.PreviewLineUp"),
    PREVIEW_HALF_DOWN("ProjectTreeNavigator.PreviewHalfDown"),
    PREVIEW_HALF_UP("ProjectTreeNavigator.PreviewHalfUp"),
    RENAME(NavigatorFileActions.RENAME),
    MOVE(NavigatorFileActions.MOVE),
    DELETE(NavigatorFileActions.DELETE),
    COPY(NavigatorFileActions.COPY),
    CUT(NavigatorFileActions.CUT),
    PASTE(NavigatorFileActions.PASTE),
    ;

    fun shortcutSet(): CustomShortcutSet =
        CustomShortcutSet(*KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts)
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
