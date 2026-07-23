package me.steveb05.ideavifm

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil

object NavigatorFileActions {

    const val NEW = "NewGroup"
    const val NEW_ELEMENT = "NewElement"
    const val RENAME = "RenameElement"
    const val MOVE = "Move"
    const val DELETE = "\$Delete"
    const val COPY = "\$Copy"
    const val CUT = "\$Cut"
    const val PASTE = "\$Paste"

    fun perform(actionId: String, dataContext: DataContext, onDone: () -> Unit) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val event = AnActionEvent.createEvent(action, dataContext, null, ActionPlaces.POPUP, ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
        onDone()
    }

    fun contextGroup(): ActionGroup {
        val manager = ActionManager.getInstance()
        val group = DefaultActionGroup()
        val sections = listOf(
            listOf(NEW),
            listOf(RENAME, MOVE),
            listOf(CUT, COPY, PASTE),
            listOf(DELETE),
        )
        for (section in sections) {
            val actions = section.mapNotNull { manager.getAction(it) }
            if (actions.isEmpty()) continue
            if (group.childrenCount > 0) group.addSeparator()
            actions.forEach { group.add(it) }
        }
        return group
    }
}
