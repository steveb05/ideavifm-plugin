package me.steveb05.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ThreeState
import java.awt.Color

object VcsStatusColor {

    private val UNCHANGED = setOf(
        FileStatus.NOT_CHANGED,
        FileStatus.NOT_CHANGED_IMMEDIATE,
        FileStatus.NOT_CHANGED_RECURSIVE,
    )

    fun forFile(project: Project, file: VirtualFile): Color? =
        colorFor(FileStatusManager.getInstance(project).getStatus(file), containsChanges = false)

    fun forDirectory(project: Project, dir: VirtualFile): Color? = colorFor(
        FileStatusManager.getInstance(project).getStatus(dir),
        containsChanges(ChangeListManager.getInstance(project).haveChangesUnder(dir)),
    )

    /**
     * haveChangesUnder answers YES when the directory is the immediate parent of a changed file and UNSURE
     * when the changes sit deeper, so every folder up to the root of the change is clean only on NO.
     */
    fun containsChanges(state: ThreeState): Boolean = state != ThreeState.NO

    /** A clean folder holding changes borrows the modified color; anything with a status of its own keeps it. */
    fun colorFor(status: FileStatus, containsChanges: Boolean): Color? {
        if (status !in UNCHANGED) return status.color
        return FileStatus.MODIFIED.color.takeIf { containsChanges }
    }
}
