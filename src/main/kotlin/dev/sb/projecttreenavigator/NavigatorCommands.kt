package dev.sb.projecttreenavigator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import javax.swing.JComponent

class NavigatorCommands(
    private val panel: JComponent,
    private val popup: JBPopup,
) {

    fun bind(shortcut: String, isEnabled: () -> Boolean = { true }, perform: () -> Unit) {
        val action = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isEnabled()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        action.registerCustomShortcutSet(CustomShortcutSet.fromString(shortcut), panel, popup)
    }
}
