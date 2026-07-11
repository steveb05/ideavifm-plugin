package dev.sb.projecttreenavigator

data class PrunedMatch<T>(val segments: List<String>, val payload: T, val weight: Int)

class PrunedTreeNode<T>(
    val name: String,
    val payload: T?,
    val weight: Int,
    val children: List<PrunedTreeNode<T>>,
)

object PrunedTreeBuilder {

    fun <T> build(matches: List<PrunedMatch<T>>): List<PrunedTreeNode<T>> {
        val root = MutableNode<T>()
        for (match in matches) {
            require(match.segments.isNotEmpty()) { "match must have at least one segment" }
            var current = root
            for (i in 0 until match.segments.size - 1) {
                current = current.folders.getOrPut(match.segments[i]) { MutableNode() }
            }
            current.files.add(PrunedTreeNode(match.segments.last(), match.payload, match.weight, emptyList()))
        }
        return freeze(root)
    }

    private class MutableNode<T> {
        val folders = LinkedHashMap<String, MutableNode<T>>()
        val files = ArrayList<PrunedTreeNode<T>>()
    }

    private fun <T> freeze(node: MutableNode<T>): List<PrunedTreeNode<T>> {
        val folders = node.folders.entries
            .map { (name, child) -> PrunedTreeNode<T>(name, null, 0, freeze(child)) }
            .sortedBy { it.name.lowercase() }
        val files = node.files.sortedBy { it.name.lowercase() }
        return folders + files
    }
}
