package me.steveb05.ideavifm.ui

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel

class IdeFocusWatcherTest : BasePlatformTestCase() {

    fun testLeavingTheIdeRunsTheCallback() {
        val parent = Disposer.newDisposable()
        Disposer.register(testRootDisposable, parent)
        var left = 0
        runWhenIdeLosesFocus(parent) { left++ }

        leaveTheIde()

        assertEquals(1, left)
    }

    fun testDisposingTheParentStopsTheCallback() {
        val parent = Disposer.newDisposable()
        var left = 0
        runWhenIdeLosesFocus(parent) { left++ }

        Disposer.dispose(parent)
        leaveTheIde()

        assertEquals(0, left)
    }

    private fun leaveTheIde() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ApplicationActivationListener.TOPIC)
            .applicationDeactivated(StubFrame())
    }

    private inner class StubFrame : IdeFrame {
        override fun getStatusBar() = null
        override fun suggestChildFrameBounds() = Rectangle()
        override fun getProject() = this@IdeFocusWatcherTest.project
        override fun setFrameTitle(title: String) = Unit
        override fun getComponent(): JComponent = JPanel()
        override fun getBalloonLayout() = null
    }
}
