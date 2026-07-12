package me.steveb05.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ThreeState
import java.awt.Color

object VcsStatusColor {

    fun forFile(project: Project, file: VirtualFile): Color? =
        FileStatusManager.getInstance(project).getStatus(file)
            .takeIf { it != FileStatus.NOT_CHANGED }
            ?.color

    fun forDirectory(project: Project, dir: VirtualFile): Color? =
        forFile(project, dir)
            ?: FileStatus.MODIFIED.color.takeIf {
                ChangeListManager.getInstance(project).haveChangesUnder(dir) == ThreeState.YES
            }
}
