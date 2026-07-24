package me.steveb05.ideavifm.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import me.steveb05.ideavifm.settings.NavigatorSettings
import me.steveb05.ideavifm.tree.BrowseTree
import me.steveb05.ideavifm.tree.NavigatorNodeData
import me.steveb05.ideavifm.tree.TreeLevel

/**
 * How far [tree] is open, and walking between the top level folders. Levels are counted the way the tree draws
 * them: a chain of folders shown as one row is one step rather than one per folder it stands for.
 */
class TreeLevels(private val project: Project, private val tree: Tree) {

    fun expandAll() = expandEverythingUnder(root())

    fun expandTopLevel() = TreeUtil.expand(tree, 1)

    /**
     * Opens the pane as far as [level] says, or as far as a module says when the pane's root is one. The walk
     * lists a module and stops; how far it opens is read from inside it, which is where the left pane putting
     * it at the root of this pane leaves you.
     */
    fun openRoot(level: TreeLevel) {
        val root = root()
        val inside = BrowseTree.isModuleFolder(root, BrowseTree.moduleRootPaths(project))
        openTo(if (inside) NavigatorSettings.getInstance().moduleOpenLevel else level, root)
    }

    /** Opens the tree as far as [level] says, from [from] when a jump is opening one folder rather than all. */
    fun openTo(level: TreeLevel, from: DefaultMutableTreeNode = root()) {
        when (level) {
            TreeLevel.NONE -> Unit
            TreeLevel.ONE -> expand(BrowseTree.levelTargets(project, model(), 1, from))
            TreeLevel.PACKAGES -> expand(BrowseTree.autoExpandTargets(project, model(), from))
            TreeLevel.ALL -> expandEverythingUnder(from)
        }
    }

    /** Opens every folder one level past the deepest level now open. */
    fun expandOneLevel() {
        for (depth in 1..MAX_LEVELS) {
            val targets = BrowseTree.levelTargets(project, model(), depth)
            if (targets.any { !tree.isExpanded(TreePath(it.path)) }) {
                expand(targets)
                return
            }
        }
    }

    /** Closes the tree back to one level shallower, folders opened deeper by hand included. */
    fun collapseOneLevel() {
        val level = currentLevel()
        if (level <= 0) return
        val keep = BrowseTree.levelTargets(project, model(), level - 1).toSet()
        for (row in tree.rowCount - 1 downTo 0) {
            val path = tree.getPathForRow(row) ?: continue
            if (!tree.isExpanded(path)) continue
            if (path.lastPathComponent !in keep) tree.collapsePath(path)
        }
    }

    /**
     * Walks to the next top level folder, opens it as far as the settings say, and closes the one being left
     * when they say that too. Answers whether there was one to walk to.
     */
    fun jumpToRootFolder(delta: Int): Boolean {
        val folders = rootFolders()
        if (folders.isEmpty()) return false
        val leaving = selectedRootFolder()
        val at = folders.indexOf(leaving)
        val next = if (at < 0) (if (delta > 0) 0 else folders.lastIndex) else at + delta
        if (next !in folders.indices) return false
        val target = folders[next]
        val settings = NavigatorSettings.getInstance()
        if (settings.closeFolderOnJump && leaving != null && leaving !== target) {
            tree.collapsePath(TreePath(leaving.path))
        }
        openJumped(target, settings.jumpOpenLevel)
        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return true
    }

    /**
     * The folder jumped to is itself the first level: opening it is all [TreeLevel.ONE] asks for, and the
     * levels past that are read inside it.
     */
    private fun openJumped(target: DefaultMutableTreeNode, level: TreeLevel) {
        if (level == TreeLevel.NONE) return
        BrowseTree.loadChildren(project, model(), target)
        tree.expandPath(TreePath(target.path))
        if (level != TreeLevel.ONE) openTo(level, target)
    }

    /** The deepest level the whole tree is open to, which is where the level keys count from. */
    private fun currentLevel(): Int {
        for (depth in 1..MAX_LEVELS) {
            val targets = BrowseTree.levelTargets(project, model(), depth)
            if (targets.any { !tree.isExpanded(TreePath(it.path)) }) return depth - 1
        }
        return MAX_LEVELS
    }

    private fun expand(nodes: List<DefaultMutableTreeNode>) {
        for (node in nodes) tree.expandPath(TreePath(node.path))
    }

    /** Walks the rows as they appear, since a folder's own children only load when it opens. */
    private fun expandEverythingUnder(from: DefaultMutableTreeNode) {
        var row = 0
        while (row < tree.rowCount) {
            val path = tree.getPathForRow(row)
            val node = path?.lastPathComponent as? DefaultMutableTreeNode
            if (node != null && isDirectory(node) && node.isNodeAncestor(from)) {
                BrowseTree.loadChildren(project, model(), node)
                tree.expandPath(path)
            }
            row++
        }
    }

    /** The top level folders of the pane, which is what a root holding many modules shows one row of each. */
    private fun rootFolders(): List<DefaultMutableTreeNode> =
        root().children().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .filter { isDirectory(it) }
            .toList()

    private fun selectedRootFolder(): DefaultMutableTreeNode? {
        val path = tree.selectionPath ?: return null
        if (path.pathCount < 2) return null
        return path.getPathComponent(1) as? DefaultMutableTreeNode
    }

    private fun isDirectory(node: DefaultMutableTreeNode): Boolean =
        (node.userObject as? NavigatorNodeData)?.isDirectory == true

    private fun model(): DefaultTreeModel = tree.model as DefaultTreeModel

    private fun root(): DefaultMutableTreeNode = model().root as DefaultMutableTreeNode

    private companion object {
        /** A tree deeper than this holds nothing a level key was going to reach one press at a time. */
        const val MAX_LEVELS = 32
    }
}
