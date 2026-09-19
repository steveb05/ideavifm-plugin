package me.steveb05.ideavifm.search

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** The path a query is matched against: relative to the project, or to the content root that holds it. */
object SearchPath {

    fun of(project: Project, file: VirtualFile): String =
        of(file, project.guessProjectDir(), ProjectFileIndex.getInstance(project))

    fun of(file: VirtualFile, base: VirtualFile?, index: ProjectFileIndex): String {
        base?.let { VfsUtilCore.getRelativePath(file, it) }?.let { return it }
        val root = index.getContentRootForFile(file) ?: return file.name
        val relative = VfsUtilCore.getRelativePath(file, root) ?: return file.name
        return "${root.name}/$relative"
    }
}
