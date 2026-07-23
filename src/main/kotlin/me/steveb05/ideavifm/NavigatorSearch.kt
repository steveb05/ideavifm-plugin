package me.steveb05.ideavifm

import com.intellij.openapi.vfs.VirtualFile

/** A class, interface or object declared inside a file, and where it sits within the file. */
data class Declaration(val name: String, val offset: Int, val weight: Int)

data class RankedFile(
    val file: VirtualFile,
    val weight: Int,
    val declarations: List<Declaration> = emptyList(),
)

data class SearchResult(val files: List<RankedFile>, val truncated: Boolean)

/**
 * One ranked list out of the two ways a query can reach a file: its own name or path ([FileNameSearch]), and
 * the classes declared inside it ([DeclarationSearch]). A file the query names keeps its rank and carries the
 * declarations it also matched, so the row can say why. A file reached only through a class it declares ranks
 * below every named file and above the ones matched through their folders: "bob" should offer People.kt,
 * which declares Bob, before bob/config/build.gradle.kts, which merely lives in a folder spelled that way.
 */
object NavigatorSearch {

    const val DECLARATION_BONUS = 50_000

    fun merge(
        named: SearchResult,
        declaring: Map<VirtualFile, List<Declaration>>,
        limit: Int = FileNameSearch.DEFAULT_LIMIT,
    ): SearchResult {
        if (declaring.isEmpty()) return named
        val merged = ArrayList<RankedFile>(named.files.size + declaring.size)
        for (file in named.files) {
            val declarations = declaring[file.file].orEmpty()
            merged.add(if (declarations.isEmpty()) file else file.copy(declarations = declarations))
        }
        val alreadyNamed = named.files.mapTo(HashSet()) { it.file }
        for ((file, declarations) in declaring) {
            if (file in alreadyNamed) continue
            val best = declarations.maxOf { it.weight }
            merged.add(RankedFile(file, DECLARATION_BONUS + best, declarations))
        }
        merged.sortByDescending { it.weight }
        if (merged.size > limit) return SearchResult(merged.subList(0, limit).toList(), true)
        return SearchResult(merged, named.truncated)
    }
}
