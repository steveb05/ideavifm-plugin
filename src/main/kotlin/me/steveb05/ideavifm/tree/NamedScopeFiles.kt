package me.steveb05.ideavifm.tree

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSetBase

object NamedScopeFiles {

    const val DEFAULT_LIMIT = 10000

    data class Result(val files: List<VirtualFile>, val truncated: Boolean)

    fun collect(project: Project, scope: NamedScope, limit: Int = DEFAULT_LIMIT): Result {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val packageSet = scope.value ?: return Result(emptyList(), false)
        val holders = listOf(
            NamedScopeManager.getInstance(project),
            DependencyValidationManager.getInstance(project),
        )
        val psiManager = PsiManager.getInstance(project)
        val files = ArrayList<VirtualFile>()
        var truncated = false
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (vf.isDirectory) return@iterateContent true
            ProgressManager.checkCanceled()
            val contained = holders.any { holder ->
                if (packageSet is PackageSetBase) packageSet.contains(vf, project, holder)
                else psiManager.findFile(vf)?.let { packageSet.contains(it, holder) } == true
            }
            if (contained) {
                if (files.size >= limit) {
                    truncated = true
                    return@iterateContent false
                }
                files.add(vf)
            }
            true
        }
        return Result(files, truncated)
    }
}
