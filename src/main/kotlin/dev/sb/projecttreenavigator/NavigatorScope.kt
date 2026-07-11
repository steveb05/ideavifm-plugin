package dev.sb.projecttreenavigator

import com.intellij.openapi.module.ModuleManager
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
    val indent: Int = 0,
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
            if (module == null) {
                projectResolved(context).copy(fellBack = true)
            } else {
                val all = ModuleManager.getInstance(context.project).modules.toList()
                val familyNames = moduleFamilyNames(all.map { it.name }, module.name).toSet()
                val family = all.filter { it.name in familyNames }.ifEmpty { listOf(module) }
                val roots = topLevelRoots(
                    family.flatMap { ModuleRootManager.getInstance(it).contentRoots.toList() },
                    null,
                )
                val searchScope = family.map { it.moduleContentScope }.reduce { a, b -> a.uniteWith(b) }
                when {
                    roots.isEmpty() -> projectResolved(context).copy(fellBack = true)
                    roots.size == 1 -> Resolved(
                        entriesForBase(context.project, roots.single()),
                        searchScope,
                        false,
                    )

                    else -> Resolved(
                        withChildEntries(context.project, withParentHints(roots.map { entryFor(context.project, it) })),
                        searchScope,
                        false,
                    )
                }
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

    fun entryFor(project: Project, dir: VirtualFile): BaseEntry {
        val (deepest, name) = BrowseTree.compactChain(project, dir)
        return BaseEntry(deepest, name, true)
    }

    fun entriesForBase(project: Project, base: VirtualFile): List<BaseEntry> =
        BrowseTree.visibleChildren(project, base).map { child ->
            if (child.isDirectory) entryFor(project, child)
            else BaseEntry(child, child.name, false)
        }

    fun topLevelRoots(roots: List<VirtualFile>, base: VirtualFile?): List<VirtualFile> =
        roots.distinct().filter { root ->
            (base == null || !VfsUtilCore.isAncestor(base, root, false)) &&
                roots.none { other -> other != root && VfsUtilCore.isAncestor(other, root, true) }
        }

    fun moduleFamilyNames(allNames: List<String>, moduleName: String): List<String> {
        val prefix = moduleName.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return listOf(moduleName)
        return allNames.filter { it == prefix || it.startsWith("$prefix.") }
    }

    fun withParentHints(entries: List<BaseEntry>): List<BaseEntry> {
        val duplicated = entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        return entries.map { entry ->
            if (entry.name in duplicated) entry.copy(parentHint = entry.file.parent?.path.orEmpty())
            else entry
        }
    }

    fun assembleProjectEntries(
        base: BaseEntry?,
        baseChildren: List<BaseEntry>,
        outside: List<BaseEntry>,
    ): List<BaseEntry> = when {
        base == null -> withParentHints(sortEntries(outside))
        outside.isEmpty() -> withParentHints(baseChildren)
        else -> withParentHints(sortEntries(listOf(base) + outside))
    }

    fun withChildEntries(project: Project, entries: List<BaseEntry>): List<BaseEntry> {
        val settings = NavigatorSettings.getInstance()
        if (!settings.leftPaneChildren) return entries
        return entries.flatMap { entry ->
            if (!entry.isDirectory) listOf(entry)
            else listOf(entry) + childEntries(project, entry, settings.leftPaneChildFiles)
        }
    }

    private fun childEntries(project: Project, parent: BaseEntry, includeFiles: Boolean): List<BaseEntry> =
        BrowseTree.visibleChildren(project, parent.file)
            .filter { it.isDirectory || includeFiles }
            .map { child ->
                if (child.isDirectory) entryFor(project, child).copy(indent = 1)
                else BaseEntry(child, child.name, false, indent = 1)
            }

    private fun sortEntries(entries: List<BaseEntry>): List<BaseEntry> =
        entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    private fun customScopes(context: NavigatorContext): List<NamedScope> =
        NamedScopeManager.getInstance(context.project).editableScopes.toList() +
            DependencyValidationManager.getInstance(context.project).editableScopes.toList()

    private fun projectResolved(context: NavigatorContext): Resolved {
        val project = context.project
        val base = project.guessProjectDir()
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots.toList()
        val outside = topLevelRoots(contentRoots, base).map {
            if (it.isDirectory) entryFor(project, it) else BaseEntry(it, it.name, false)
        }
        val assembled = assembleProjectEntries(
            base?.let { entryFor(project, it) },
            base?.let { entriesForBase(project, it) }.orEmpty(),
            outside,
        )
        val entries =
            if (outside.isEmpty() && base != null) assembled
            else withChildEntries(project, assembled)
        return Resolved(entries, ProjectScope.getContentScope(project), false)
    }
}
