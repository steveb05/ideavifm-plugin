package me.steveb05.ideavifm.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.IdeFrame

/**
 * Runs [onFocusLeft] when the focus leaves the IDE, until [parent] is disposed.
 *
 * The platform reports this per application rather than per window, and only once the focus lands outside
 * the IDE altogether: the dialogs opened from a popup keep it inside, so they do not count as leaving.
 * Only the application bus carries the topic, since it is declared to broadcast to no child bus.
 */
fun runWhenIdeLosesFocus(parent: Disposable, onFocusLeft: () -> Unit) {
    ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
        ApplicationActivationListener.TOPIC,
        object : ApplicationActivationListener {
            override fun applicationDeactivated(ideFrame: IdeFrame) = onFocusLeft()
        },
    )
}
