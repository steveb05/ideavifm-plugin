package me.steveb05.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

object VcsStatusColor {

    fun forFile(project: Project, file: VirtualFile): Color? =
        colorOf(FileStatusManager.getInstance(project).getStatus(file))

    fun forDirectory(project: Project, dir: VirtualFile): Color? =
        colorOf(FileStatusManager.getInstance(project).getRecursiveStatus(dir))

    /**
     * A clean directory that contains changes reports NOT_CHANGED_IMMEDIATE (a changed file sits directly
     * in it) or NOT_CHANGED_RECURSIVE (the changes are deeper). Both mean "contains changes", and both
     * leave their scheme color undefined unless the user sets one, so they fall back to the modified color.
     */
    fun colorOf(status: FileStatus): Color? = when (status) {
        FileStatus.NOT_CHANGED -> null
        FileStatus.NOT_CHANGED_IMMEDIATE, FileStatus.NOT_CHANGED_RECURSIVE ->
            status.color ?: FileStatus.MODIFIED.color

        else -> status.color
    }
}
