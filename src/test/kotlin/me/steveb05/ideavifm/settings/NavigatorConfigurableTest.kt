package me.steveb05.ideavifm.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The panel binds its controls through the UI DSL, which resolves nothing until the panel is built: a control
 * bound to the wrong kind of value is a settings page that fails to open rather than a compile error.
 */
class NavigatorConfigurableTest : BasePlatformTestCase() {

    private lateinit var settings: NavigatorSettings
    private lateinit var original: NavigatorSettings.State

    override fun setUp() {
        super.setUp()
        settings = NavigatorSettings.getInstance()
        original = settings.state.copy()
    }

    override fun tearDown() {
        try {
            settings.loadState(original)
        } finally {
            super.tearDown()
        }
    }

    fun testTheHoverBoxRoundTripsThroughThePanel() {
        val panel = NavigatorConfigurable().createPanel()
        for (hover in listOf(true, false)) {
            settings.previewOnHover = hover
            panel.reset()
            assertFalse(panel.isModified())
            panel.apply()
            assertEquals(hover, settings.previewOnHover)
        }
    }
}
