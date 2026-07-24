package me.steveb05.ideavifm.tree

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
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

    /** A folder that has been deleted holds nothing, and reading children off one throws rather than saying so. */
    fun visibleChildren(project: Project, dir: VirtualFile): List<VirtualFile> {
        if (!dir.isValid || !dir.isDirectory) return emptyList()
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            val hideDots = NavigatorSettings.getInstance().hideDotFiles
            dir.children
                .filter { it.isValid && !index.isExcluded(it) && !(hideDots && it.name.startsWith(".")) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
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
     * A folder the walk is considering: whether it is the only folder at its own level, and whether a chain
     * of folders has already been crossed on the way down to it.
     */
    private data class Step(
        val node: DefaultMutableTreeNode,
        val depth: Int,
        val alone: Boolean,
        val pastAChain: Boolean = false,
    )

    /**
     * The folders to open so that what a folder holds comes into view, walked one branch at a time. A module
     * is content of its own: the walk lists it and stops there, since how far a module opens is read from
     * inside it. A folder holding modules is scaffolding whatever files of its own it carries, so a
     * settings.gradle.kts beside a row of modules does not stop the walk.
     */
    fun autoExpandTargets(
        project: Project,
        model: DefaultTreeModel,
        from: DefaultMutableTreeNode = model.root as DefaultMutableTreeNode,
        maxDepth: Int = 8,
        maxNodes: Int = 200,
        moduleRoots: Set<String> = moduleRootPaths(project),
    ): List<DefaultMutableTreeNode> {
        val openLoneFolder = NavigatorSettings.getInstance().openLoneFolder
        val targets = ArrayList<DefaultMutableTreeNode>()
        loadChildren(project, model, from)
        val pending = ArrayDeque<Step>()
        val top = directoryChildren(from)
        top.forEach { pending.addLast(Step(it, 0, alone = top.size == 1)) }
        while (pending.isNotEmpty() && targets.size < maxNodes) {
            val step = pending.removeFirst()
            if (step.depth >= maxDepth) continue
            loadChildren(project, model, step.node)
            val children = directoryChildren(step.node)
            val scaffolding = children.any { isModuleFolder(it, moduleRoots) }
            if (!scaffolding && isModuleFolder(step.node, moduleRoots)) continue
            if (!scaffolding && !opens(step, openLoneFolder)) continue
            targets.add(step.node)
            if (!scaffolding && holdsFiles(step.node)) continue
            val pastAChain = step.pastAChain || standsForAChain(step.node)
            children.forEach {
                pending.addLast(Step(it, step.depth + 1, children.size == 1, pastAChain))
            }
        }
        return targets
    }

    /**
     * The folders the IDE knows as projects of their own. A build tool's own idea of where a project sits wins,
     * since it names the folder a developer means by a module; without one, the modules' content roots say it.
     */
    fun moduleRootPaths(project: Project): Set<String> {
        val modules = ModuleManager.getInstance(project).modules
        val external = modules.mapNotNullTo(HashSet()) { ExternalSystemApiUtil.getExternalProjectPath(it) }
        if (external.isNotEmpty()) return external
        return modules.flatMapTo(HashSet()) { module ->
            ModuleRootManager.getInstance(module).contentRoots.map { it.path }
        }
    }

    /** Whether [node] stands for one of [moduleRoots], the folders the IDE knows as projects of their own. */
    fun isModuleFolder(node: DefaultMutableTreeNode, moduleRoots: Set<String>): Boolean =
        (node.userObject as? NavigatorNodeData)?.file?.path in moduleRoots

    /**
     * A folder opens while it holds nothing but more folders, so the walk crosses the folders a package chain
     * is made of and stops on the packages themselves. Files of its own are what make a folder content rather
     * than structure, and content is left closed: a package holding a handful of classes is no more worth
     * opening for having a subpackage among them, and neither is a folder holding a couple of files. The
     * chain that leads from the folders into the packages is the exception, and a folder with no other folder
     * beside it reads the same way, which [openLoneFolder] can stop.
     */
    private fun opens(step: Step, openLoneFolder: Boolean): Boolean =
        !holdsFiles(step.node) || crossesIntoThePackages(step) || (step.alone && openLoneFolder)

    /**
     * Whether the row is the chain that carries the walk from the folders into the packages: it spells a
     * chain, folders sit at the end of it, and nothing above it was a chain. Showing where such a row lands is
     * the whole reason it is drawn as one row, files among the packages there or not. Deeper down a chain
     * runs from one package to the next, where it is a package like any other: a data/entities row holds its
     * classes back the way its plain neighbours do.
     */
    private fun crossesIntoThePackages(step: Step): Boolean =
        !step.pastAChain && standsForAChain(step.node) && directoryChildren(step.node).isNotEmpty()

    /** Whether the row spells a chain of folders rather than naming one, which compacting them is what does. */
    private fun standsForAChain(node: DefaultMutableTreeNode): Boolean {
        val data = node.userObject as? NavigatorNodeData ?: return false
        val file = data.file ?: return false
        return data.name != file.name
    }

    /**
     * The folders to open to reach [depth] levels inside [from]. A folder that is the only folder at its level
     * shares the level of the one holding it: it shows as a single row standing for a chain, so opening the
     * chain is part of opening its parent rather than a step of its own.
     */
    fun levelTargets(
        project: Project,
        model: DefaultTreeModel,
        depth: Int,
        from: DefaultMutableTreeNode = model.root as DefaultMutableTreeNode,
        maxNodes: Int = 200,
    ): List<DefaultMutableTreeNode> {
        if (depth <= 0) return emptyList()
        val targets = ArrayList<DefaultMutableTreeNode>()
        loadChildren(project, model, from)
        val pending = ArrayDeque<Pair<DefaultMutableTreeNode, Int>>()
        directoryChildren(from).forEach { pending.addLast(it to 1) }
        while (pending.isNotEmpty() && targets.size < maxNodes) {
            val (node, level) = pending.removeFirst()
            if (level > depth) continue
            loadChildren(project, model, node)
            targets.add(node)
            val children = directoryChildren(node)
            val childLevel = if (children.size == 1) level else level + 1
            children.forEach { pending.addLast(it to childLevel) }
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
