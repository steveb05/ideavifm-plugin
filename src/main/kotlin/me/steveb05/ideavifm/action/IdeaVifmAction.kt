package me.steveb05.ideavifm.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import me.steveb05.ideavifm.ui.NavigatorContext
import me.steveb05.ideavifm.ui.NavigatorPopup

class IdeaVifmAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        NavigatorPopup(NavigatorContext.capture(project, file)).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
