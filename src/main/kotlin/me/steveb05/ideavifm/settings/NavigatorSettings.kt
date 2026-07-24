package me.steveb05.ideavifm.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import me.steveb05.ideavifm.search.DeclarationDepth
import me.steveb05.ideavifm.tree.TreeLevel

@Service
@State(name = "IdeaVifmSettings", storages = [Storage("idea-vifm.xml")])
class NavigatorSettings : PersistentStateComponent<NavigatorSettings.State> {

    data class State(
        var hideDotFiles: Boolean = true,
        var compactFolders: Boolean = true,
        var openLoneFolder: Boolean = true,
        var treeOpenLevel: TreeLevel = TreeLevel.PACKAGES,
        var jumpOpenLevel: TreeLevel = TreeLevel.PACKAGES,
        var moduleOpenLevel: TreeLevel = TreeLevel.PACKAGES,
        var closeFolderOnJump: Boolean = true,
        var showPreview: Boolean = false,
        var previewOnHover: Boolean = false,
        var leftPaneChildren: Boolean = true,
        var leftPaneChildFiles: Boolean = true,
        var restoreLastView: Boolean = false,
        var openCreatedFile: Boolean = true,
        var declarationDepth: DeclarationDepth = DeclarationDepth.TOP_LEVEL,
    )

    private var current = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    var hideDotFiles: Boolean
        get() = current.hideDotFiles
        set(value) {
            current.hideDotFiles = value
        }

    var compactFolders: Boolean
        get() = current.compactFolders
        set(value) {
            current.compactFolders = value
        }

    var openLoneFolder: Boolean
        get() = current.openLoneFolder
        set(value) {
            current.openLoneFolder = value
        }

    var treeOpenLevel: TreeLevel
        get() = current.treeOpenLevel
        set(value) {
            current.treeOpenLevel = value
        }

    var jumpOpenLevel: TreeLevel
        get() = current.jumpOpenLevel
        set(value) {
            current.jumpOpenLevel = value
        }

    var moduleOpenLevel: TreeLevel
        get() = current.moduleOpenLevel
        set(value) {
            current.moduleOpenLevel = value
        }

    var closeFolderOnJump: Boolean
        get() = current.closeFolderOnJump
        set(value) {
            current.closeFolderOnJump = value
        }

    var showPreview: Boolean
        get() = current.showPreview
        set(value) {
            current.showPreview = value
        }

    var previewOnHover: Boolean
        get() = current.previewOnHover
        set(value) {
            current.previewOnHover = value
        }

    var leftPaneChildren: Boolean
        get() = current.leftPaneChildren
        set(value) {
            current.leftPaneChildren = value
        }

    var leftPaneChildFiles: Boolean
        get() = current.leftPaneChildFiles
        set(value) {
            current.leftPaneChildFiles = value
        }

    var restoreLastView: Boolean
        get() = current.restoreLastView
        set(value) {
            current.restoreLastView = value
        }

    var openCreatedFile: Boolean
        get() = current.openCreatedFile
        set(value) {
            current.openCreatedFile = value
        }

    var declarationDepth: DeclarationDepth
        get() = current.declarationDepth
        set(value) {
            current.declarationDepth = value
        }

    companion object {
        fun getInstance(): NavigatorSettings =
            ApplicationManager.getApplication().getService(NavigatorSettings::class.java)
    }
}
