package dev.sb.projecttreenavigator

import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
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

object ScopeResolver {

    data class Resolved(
        val roots: List<VirtualFile>,
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
            if (module == null) projectResolved(context).copy(fellBack = true)
            else Resolved(
                ModuleRootManager.getInstance(module).contentRoots.toList(),
                module.moduleContentScope,
                false,
            )
        }

        NavigatorScope.Folder -> {
            val dir = context.currentFile?.parent
            if (dir == null || !dir.isValid) projectResolved(context).copy(fellBack = true)
            else Resolved(
                listOf(dir),
                GlobalSearchScopesCore.directoryScope(context.project, dir, true),
                false,
            )
        }

        is NavigatorScope.Named -> Resolved(
            ProjectRootManager.getInstance(context.project).contentRoots.toList(),
            GlobalSearchScopesCore.filterScope(context.project, scope.namedScope),
            false,
        )
    }

    private fun customScopes(context: NavigatorContext): List<NamedScope> =
        NamedScopeManager.getInstance(context.project).editableScopes.toList() +
            DependencyValidationManager.getInstance(context.project).editableScopes.toList()

    private fun projectResolved(context: NavigatorContext): Resolved = Resolved(
        ProjectRootManager.getInstance(context.project).contentRoots.toList(),
        ProjectScope.getContentScope(context.project),
        false,
    )
}
