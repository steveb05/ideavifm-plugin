package me.steveb05.ideavifm.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import javax.swing.JComponent
import me.steveb05.ideavifm.file.NavigatorFileActions

enum class NavigatorCommand(val actionId: String) {
    LEFT_UP("IdeaVifm.LeftUp"),
    LEFT_DOWN("IdeaVifm.LeftDown"),
    RIGHT_UP("IdeaVifm.RightUp"),
    RIGHT_DOWN("IdeaVifm.RightDown"),
    PANE_LEFT("IdeaVifm.PaneLeft"),
    PANE_RIGHT("IdeaVifm.PaneRight"),
    ZOOM_IN("IdeaVifm.ZoomIn"),
    ZOOM_OUT("IdeaVifm.ZoomOut"),
    TOGGLE_PREVIEW("IdeaVifm.TogglePreview"),
    TOGGLE_DOT_FILES("IdeaVifm.ToggleDotFiles"),
    TOGGLE_CHANGED("IdeaVifm.ToggleChangedOnly"),
    TOGGLE_MARK("IdeaVifm.ToggleMark"),
    FOCUS_SEARCH("IdeaVifm.FocusSearch"),
    RESET_TREE("IdeaVifm.ResetTree"),
    NEW_ELEMENT(NavigatorFileActions.NEW_ELEMENT),
    NEW_ELEMENT_INVERTED("IdeaVifm.NewElementInverted"),
    PREVIEW_LINE_DOWN("IdeaVifm.PreviewLineDown"),
    PREVIEW_LINE_UP("IdeaVifm.PreviewLineUp"),
    PREVIEW_HALF_DOWN("IdeaVifm.PreviewHalfDown"),
    PREVIEW_HALF_UP("IdeaVifm.PreviewHalfUp"),
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
