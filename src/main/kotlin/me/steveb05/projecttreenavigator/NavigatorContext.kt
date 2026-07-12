package me.steveb05.projecttreenavigator

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

data class NavigatorContext(
    val project: Project,
    val currentFile: VirtualFile?,
    val module: Module?,
) {
    companion object {
        fun capture(project: Project, file: VirtualFile?): NavigatorContext {
            val inContent = file != null && file.isValid &&
                ProjectFileIndex.getInstance(project).isInContent(file)
            val effective = if (inContent) file else null
            val module = effective?.let { ModuleUtilCore.findModuleForFile(it, project) }
            return NavigatorContext(project, effective, module)
        }
    }
}
