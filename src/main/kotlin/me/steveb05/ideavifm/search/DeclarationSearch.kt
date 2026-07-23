package me.steveb05.ideavifm.search

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters

/**
 * How much of what a file declares a query is matched against.
 *
 * [CLASSES] is what Go to Class offers. [SYMBOLS] is what Go to Symbol offers: every function, property and
 * field as well, which is thorough and noisy, since a short query matches a great many members. [TOP_LEVEL]
 * sits between the two, keeping the symbols a file declares at its own level and dropping the ones a class
 * holds: Kotlin extension functions, top level functions, properties and type aliases.
 */
enum class DeclarationDepth(val label: String) {
    CLASSES("Classes only"),
    TOP_LEVEL("Classes and top level declarations"),
    SYMBOLS("All symbols"),
    ;

    fun next(): DeclarationDepth = DeclarationDepth.entries[(ordinal + 1) % DeclarationDepth.entries.size]
}

/**
 * The files that declare something the query matches, so searching "bob" reaches People.kt when Bob is a class
 * inside it, and Strings.kt when bobcase is a function inside it. The names come from the contributors behind
 * Go to Class and Go to Symbol, one per language, which read them from the index: no file is opened and no PSI
 * is built until a name matches.
 */
class DeclarationSearch(private val project: Project) {

    fun search(
        rawQuery: String,
        scope: GlobalSearchScope,
        depth: DeclarationDepth = DeclarationDepth.TOP_LEVEL,
    ): Map<VirtualFile, List<Declaration>> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val query = rawQuery.trim().trim('/')
        if (query.length < MIN_QUERY || query.contains('/')) return emptyMap()

        val matcher = FileNameSearch.nameMatcher(query)
        val declaring = LinkedHashMap<VirtualFile, MutableList<Declaration>>()
        collect(
            ChooseByNameContributor.CLASS_EP_NAME.extensionList,
            matcher,
            scope,
            topLevelOnly = false,
            declaring,
        )
        if (depth != DeclarationDepth.CLASSES) {
            collect(
                ChooseByNameContributor.SYMBOL_EP_NAME.extensionList,
                matcher,
                scope,
                topLevelOnly = depth == DeclarationDepth.TOP_LEVEL,
                declaring,
            )
        }
        return freeze(declaring)
    }

    private fun collect(
        contributors: List<ChooseByNameContributor>,
        matcher: MinusculeMatcher,
        scope: GlobalSearchScope,
        topLevelOnly: Boolean,
        declaring: MutableMap<VirtualFile, MutableList<Declaration>>,
    ) {
        for (contributor in contributors) {
            ProgressManager.checkCanceled()
            for (name in bestNames(contributor, matcher, scope)) {
                if (declaring.size >= MAX_FILES) return
                collectDeclarations(
                    contributor,
                    name,
                    matcher.matchingDegree(name),
                    scope,
                    topLevelOnly,
                    declaring,
                )
            }
        }
    }

    /**
     * Matching a name is cheap, resolving one to its declaration is not, so only the best names are resolved.
     * A two letter query matches a good part of a large project's classes; the ones that match it well are
     * the ones worth showing.
     */
    private fun bestNames(
        contributor: ChooseByNameContributor,
        matcher: MinusculeMatcher,
        scope: GlobalSearchScope,
    ): List<String> {
        val matched = ArrayList<String>()
        val keep = Processor<String> { name ->
            ProgressManager.checkCanceled()
            if (matcher.matches(name)) matched.add(name)
            matched.size < MAX_NAMES
        }
        if (contributor is ChooseByNameContributorEx) {
            contributor.processNames(keep, scope, null)
        } else {
            for (name in contributor.getNames(project, false)) {
                if (!keep.process(name)) break
            }
        }
        return matched
            .sortedByDescending { matcher.matchingDegree(it) }
            .take(MAX_RESOLVED_NAMES)
    }

    private fun collectDeclarations(
        contributor: ChooseByNameContributor,
        name: String,
        weight: Int,
        scope: GlobalSearchScope,
        topLevelOnly: Boolean,
        declaring: MutableMap<VirtualFile, MutableList<Declaration>>,
    ) {
        val take = Processor<NavigationItem> { item ->
            ProgressManager.checkCanceled()
            val declared = declarationOf(item, name, weight, scope, topLevelOnly)
            if (declared != null) declaring.getOrPut(declared.first) { ArrayList() }.add(declared.second)
            true
        }
        if (contributor is ChooseByNameContributorEx) {
            contributor.processElementsWithName(name, take, FindSymbolParameters(name, name, scope))
            return
        }
        contributor.getItemsByName(name, name, project, false).forEach { take.process(it) }
    }

    /**
     * The element the IDE would navigate to. A language with light classes, Kotlin among them, hands out a
     * class whose file is the compiled shadow of the source; its navigation element is the declaration the
     * user wrote, which is the one to jump to. A declaration sits at the top level when the file itself is
     * what holds it, which is where an extension function is written.
     */
    private fun declarationOf(
        item: NavigationItem,
        name: String,
        weight: Int,
        scope: GlobalSearchScope,
        topLevelOnly: Boolean,
    ): Pair<VirtualFile, Declaration>? {
        val element = (item as? PsiElement)?.navigationElement ?: return null
        if (topLevelOnly && element.parent !is PsiFile) return null
        val file = PsiUtilCore.getVirtualFile(element) ?: return null
        if (!file.isValid || file.isDirectory || !scope.contains(file)) return null
        return file to Declaration(name, element.textOffset, weight)
    }

    private fun freeze(
        declaring: Map<VirtualFile, List<Declaration>>,
    ): Map<VirtualFile, List<Declaration>> =
        declaring.mapValues { (_, declarations) ->
            declarations.distinctBy { it.name }.sortedByDescending { it.weight }
        }

    private companion object {
        /** A single letter matches a class in most files, which would bury the files named after the query. */
        const val MIN_QUERY = 2
        const val MAX_NAMES = 2000
        const val MAX_RESOLVED_NAMES = 100
        const val MAX_FILES = 500
    }
}
