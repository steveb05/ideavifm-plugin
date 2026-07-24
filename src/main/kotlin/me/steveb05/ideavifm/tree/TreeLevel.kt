package me.steveb05.ideavifm.tree

/**
 * How far the navigator tree opens on its own. [PACKAGES] is the walk that crosses the folders a package chain
 * is made of and stops on the packages, which is what a single module wants; a root holding many modules is
 * better off at [NONE], with a key opening a level at a time from there.
 */
enum class TreeLevel(val label: String) {
    NONE("Nothing, just the top level rows"),
    ONE("One level in"),
    PACKAGES("Down to the packages"),
    ALL("Everything"),
}
