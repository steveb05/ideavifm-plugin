# Project Tree Navigator

IntelliJ IDEA plugin: a keyboard driven popup that shows the project file
tree, filtered in place with Search Everywhere style matching.

## Usage

Press `Ctrl+Alt+E` (remappable: Settings, Keymap, "Project Tree Navigator").

| Key | Action |
| --- | --- |
| type | Filter the tree in place (camel humps, fuzzy, `dir/name` paths) |
| Up / Down, Ctrl+K / Ctrl+J | Move selection |
| Left / Right (empty query), Ctrl+H / Ctrl+L | Collapse / expand |
| Enter | Open file, or toggle folder |
| Ctrl+Enter | Zoom into selected folder |
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
