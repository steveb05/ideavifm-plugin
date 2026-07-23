package me.steveb05.ideavifm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/** Where the navigator was left: which scope, which zoom, and what was selected in either pane. */
@Service(Service.Level.PROJECT)
@State(name = "IdeaVifm", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class NavigatorViewState : PersistentStateComponent<NavigatorViewState.State> {

    data class State(
        var scope: String = "",
        var zoom: MutableList<String> = mutableListOf(),
        var entry: String = "",
        var file: String = "",
    )

    private var current = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    fun save(scope: String, zoom: List<VirtualFile>, entry: VirtualFile?, file: VirtualFile?) {
        current = State(
            scope = scope,
            zoom = zoom.mapTo(mutableListOf()) { it.url },
            entry = entry?.url.orEmpty(),
            file = file?.url.orEmpty(),
        )
    }

    fun scope(): String = current.scope

    fun zoom(): List<VirtualFile> = current.zoom.mapNotNull { directory(it) }

    fun entry(): VirtualFile? = directory(current.entry)

    fun file(): VirtualFile? = existing(current.file)

    private fun directory(url: String): VirtualFile? = existing(url)?.takeIf { it.isDirectory }

    private fun existing(url: String): VirtualFile? {
        if (url.isEmpty()) return null
        return VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid }
    }

    companion object {
        fun getInstance(project: Project): NavigatorViewState = project.service()
    }
}
