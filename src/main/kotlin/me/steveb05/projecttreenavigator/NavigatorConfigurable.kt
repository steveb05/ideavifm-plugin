package me.steveb05.projecttreenavigator

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class NavigatorConfigurable : BoundConfigurable("Project Tree Navigator") {

    override fun createPanel() = panel {
        val settings = NavigatorSettings.getInstance()
        row {
            checkBox("Hide dot files and folders").bindSelected(settings::hideDotFiles)
        }
        row {
            checkBox("Compact single child folder chains").bindSelected(settings::compactFolders)
        }
        lateinit var children: Cell<JBCheckBox>
        row {
            children = checkBox("Show children of top level folders in the left pane")
                .bindSelected(settings::leftPaneChildren)
        }
        indent {
            row {
                checkBox("Include files among those children")
                    .bindSelected(settings::leftPaneChildFiles)
                    .enabledIf(children.selected)
            }
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
