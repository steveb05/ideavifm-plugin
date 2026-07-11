package dev.sb.projecttreenavigator

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class NavigatorConfigurable : BoundConfigurable("Project Tree Navigator") {

    override fun createPanel() = panel {
        val settings = NavigatorSettings.getInstance()
        row {
            checkBox("Hide dot files and folders").bindSelected(settings::hideDotFiles)
        }
        row {
            checkBox("Compact single child folder chains").bindSelected(settings::compactFolders)
        }
        row {
            checkBox("Show preview pane").bindSelected(settings::showPreview)
        }
        row {
            comment(
                "Inside the popup: Ctrl+Period toggles dot files, Alt+P toggles the preview. " +
                    "All navigator commands are remappable in Settings, Keymap.",
            )
        }
    }
}
