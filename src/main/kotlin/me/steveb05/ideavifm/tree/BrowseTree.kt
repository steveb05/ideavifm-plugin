package me.steveb05.ideavifm.tree

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import me.steveb05.ideavifm.search.Declaration
import me.steveb05.ideavifm.settings.NavigatorSettings

data class NavigatorNodeData(
    val file: VirtualFile?,
    val name: String,
    val isDirectory: Boolean,
    val weight: Int = 0,
    val declarations: List<Declaration> = emptyList(),
)

object BrowseTree {

    private val PLACEHOLDER = NavigatorNodeData(null, "loading", false)
    private const val DOT_WALK_CAP = 32
    private const val CHAIN_CAP = 32

    fun createSubtreeModel(project: Project, base: VirtualFile): DefaultTreeModel {
        val hiddenRoot = DefaultMutableTreeNode(NavigatorNodeData(base, base.name, true))
        for (child in visibleChildren(project, base)) {
            if (child.isDirectory) hiddenRoot.add(directoryNode(project, child))
            else hiddenRoot.add(DefaultMutableTreeNode(NavigatorNodeData(child, child.name, false)))
        }
        return DefaultTreeModel(hiddenRoot)
    }

    fun isLoaded(node: DefaultMutableTreeNode): Boolean =
        node.childCount != 1 ||
            (node.firstChild as DefaultMutableTreeNode).userObject !== PLACEHOLDER

    fun loadChildren(project: Project, model: DefaultTreeModel, node: DefaultMutableTreeNode) {
        if (isLoaded(node)) return
        val dir = (node.userObject as NavigatorNodeData).file ?: return
        node.removeAllChildren()
        for (child in visibleChildren(project, dir)) {
            if (child.isDirectory) node.add(directoryNode(project, child))
            else node.add(DefaultMutableTreeNode(NavigatorNodeData(child, child.name, false)))
        }
        model.nodeStructureChanged(node)
    }

    fun visibleChildren(project: Project, dir: VirtualFile): List<VirtualFile> =
        ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            val hideDots = NavigatorSettings.getInstance().hideDotFiles
            dir.children
                .filter { it.isValid && !index.isExcluded(it) && !(hideDots && it.name.startsWith(".")) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

    fun hiddenByDotRule(project: Project, file: VirtualFile): Boolean {
        if (!NavigatorSettings.getInstance().hideDotFiles) return false
        val index = ProjectFileIndex.getInstance(project)
        var current: VirtualFile? = file
        var depth = 0
        while (current != null && depth < DOT_WALK_CAP) {
            if (index.getContentRootForFile(current) == current) return false
            if (current.name.startsWith(".")) return true
            current = current.parent
            depth++
        }
        return false
    }

    fun compactChain(project: Project, dir: VirtualFile): Pair<VirtualFile, String> {
        var deepest = dir
        val names = StringBuilder(dir.name)
        if (!NavigatorSettings.getInstance().compactFolders) return deepest to names.toString()
        var depth = 0
        while (depth < CHAIN_CAP) {
            val only = visibleChildren(project, deepest).singleOrNull()?.takeIf { it.isDirectory } ?: break
            deepest = only
            names.append('/').append(only.name)
            depth++
        }
        return deepest to names.toString()
    }

    /**
     * The folders to open so that files come into view, walked one branch at a time: a folder opens, and its
     * subfolders keep opening until one of them holds files. A file sitting next to a folder does not stop
     * that folder from opening, which is what a module looks like, with build.gradle.kts beside src.
     */
    fun autoExpandTargets(
        project: Project,
        model: DefaultTreeModel,
        maxDepth: Int = 8,
        maxNodes: Int = 200,
    ): List<DefaultMutableTreeNode> {
        val targets = ArrayList<DefaultMutableTreeNode>()
        val root = model.root as DefaultMutableTreeNode
        loadChildren(project, model, root)
        val pending = ArrayDeque<Pair<DefaultMutableTreeNode, Int>>()
        directoryChildren(root).forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty() && targets.size < maxNodes) {
            val (node, depth) = pending.removeFirst()
            if (depth >= maxDepth) continue
            loadChildren(project, model, node)
            targets.add(node)
            if (holdsFiles(node)) continue
            directoryChildren(node).forEach { pending.addLast(it to depth + 1) }
        }
        return targets
    }

    private fun directoryChildren(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> =
        node.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .filter { (it.userObject as? NavigatorNodeData)?.isDirectory == true }
            .toList()

    private fun holdsFiles(node: DefaultMutableTreeNode): Boolean =
        node.children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .any { (it.userObject as? NavigatorNodeData)?.isDirectory == false }

    private fun directoryNode(project: Project, dir: VirtualFile): DefaultMutableTreeNode {
        val (deepest, name) = compactChain(project, dir)
        val node = DefaultMutableTreeNode(NavigatorNodeData(deepest, name, true))
        node.add(DefaultMutableTreeNode(PLACEHOLDER))
        return node
    }
}
