package dev.sb.projecttreenavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager

sealed class NavigatorScope(val label: String) {
    object Project : NavigatorScope("Project")
    object Module : NavigatorScope("Module")
    object Folder : NavigatorScope("Folder")
    class Named(val namedScope: NamedScope) : NavigatorScope(namedScope.presentableName)
}

data class BaseEntry(
    val file: VirtualFile,
    val name: String,
    val isDirectory: Boolean,
    val parentHint: String? = null,
)

object ScopeResolver {

    data class Resolved(
        val entries: List<BaseEntry>,
        val searchScope: GlobalSearchScope,
        val fellBack: Boolean,
    )

    fun availableScopes(context: NavigatorContext): List<NavigatorScope> =
        listOf(NavigatorScope.Project, NavigatorScope.Module, NavigatorScope.Folder) +
            customScopes(context).map { NavigatorScope.Named(it) }

    fun resolve(scope: NavigatorScope, context: NavigatorContext): Resolved = when (scope) {
        NavigatorScope.Project -> projectResolved(context)

        NavigatorScope.Module -> {
            val module = context.module
            val roots = module?.let { ModuleRootManager.getInstance(it).contentRoots.toList() }.orEmpty()
            when {
                module == null || roots.isEmpty() -> projectResolved(context).copy(fellBack = true)
                roots.size == 1 -> Resolved(
                    entriesForBase(context.project, roots.single()),
                    module.moduleContentScope,
                    false,
                )
                else -> Resolved(
                    withParentHints(roots.map { BaseEntry(it, it.name, true) }),
                    module.moduleContentScope,
                    false,
                )
            }
        }

        NavigatorScope.Folder -> {
            val dir = context.currentFile?.parent
            if (dir == null || !dir.isValid) projectResolved(context).copy(fellBack = true)
            else Resolved(
                entriesForBase(context.project, dir),
                GlobalSearchScopesCore.directoryScope(context.project, dir, true),
                false,
            )
        }

        is NavigatorScope.Named -> projectResolved(context).copy(
            searchScope = GlobalSearchScopesCore.filterScope(context.project, scope.namedScope),
        )
    }

    fun entriesForBase(project: Project, base: VirtualFile): List<BaseEntry> =
        BrowseTree.visibleChildren(project, base).map { BaseEntry(it, it.name, it.isDirectory) }

    fun topLevelRoots(roots: List<VirtualFile>, base: VirtualFile?): List<VirtualFile> =
        roots.distinct().filter { root ->
            (base == null || !VfsUtilCore.isAncestor(base, root, false)) &&
                roots.none { other -> other != root && VfsUtilCore.isAncestor(other, root, true) }
        }

    fun withParentHints(entries: List<BaseEntry>): List<BaseEntry> {
        val duplicated = entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        return entries.map { entry ->
            if (entry.name in duplicated) entry.copy(parentHint = entry.file.parent?.path.orEmpty())
            else entry
        }
    }

    private fun customScopes(context: NavigatorContext): List<NamedScope> =
        NamedScopeManager.getInstance(context.project).editableScopes.toList() +
            DependencyValidationManager.getInstance(context.project).editableScopes.toList()

    private fun projectResolved(context: NavigatorContext): Resolved {
        val project = context.project
        val base = project.guessProjectDir()
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots.toList()
        val outside = topLevelRoots(contentRoots, base).map { BaseEntry(it, it.name, it.isDirectory) }
        val entries =
            if (base == null) withParentHints(outside)
            else withParentHints(entriesForBase(project, base) + outside)
        return Resolved(entries, ProjectScope.getContentScope(project), false)
    }
}
