# Project Tree Navigator

IntelliJ IDEA plugin: a keyboard driven two pane popup that shows the
project file tree, filtered in place with Search Everywhere style
matching. The left pane lists the top level entries of the active scope
(so Gradle's nested `src/main` and `src/test` content roots never clutter
the top level); the right pane shows the selected entry's subtree. Icons
come from the IDE's icon providers, so icon packs such as Atom Material
apply.

## Usage

Press `Ctrl+Alt+E` (remappable: Settings, Keymap, "Project Tree Navigator").

| Key | Action |
| --- | --- |
| type | Search the whole scope; the left pane shows per entry match counts, the right pane shows the selected entry's matches |
| Up / Down, Ctrl+K / Ctrl+J | Move selection in the active pane; in the left pane the right pane refills live |
| Left / Right (empty query), Ctrl+H / Ctrl+L | Collapse / expand in the right pane; at a top level row Ctrl+H moves to the left pane, Ctrl+L in the left pane moves back |
| Enter | Open file, toggle folder, or enter the right pane from a left pane folder |
| Ctrl+Enter | Zoom: the selected folder becomes the base of both panes |
| Backspace (empty query) | Zoom out |
| Tab / Shift+Tab | Cycle scope: Project, Module, Folder, custom scopes |
| Esc | Close |

Custom scopes defined in Settings, Appearance and Behavior, Scopes appear
as extra chips after Folder.

## Build

- `./gradlew runIde` starts a sandbox IDE with the plugin.
- `./gradlew buildPlugin` creates the zip under `build/distributions`,
  installable via Settings, Plugins, Install Plugin from Disk.
- `./gradlew test` runs the test suite.
