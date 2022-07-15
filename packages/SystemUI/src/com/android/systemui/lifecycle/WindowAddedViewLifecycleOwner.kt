package com.android.systemui.lifecycle

import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * [LifecycleOwner] for Window-added Views.
 *
 * These are [View] instances that are added to a `Window` using the `WindowManager` API.
 *
 * This implementation goes to:
 * * The <b>CREATED</b> `Lifecycle.State` when the view gets attached to the window but the window
 * is not yet visible
 * * The <b>STARTED</b> `Lifecycle.State` when the view is attached to the window and the window is
 * visible
 * * The <b>RESUMED</b> `Lifecycle.State` when the view is attached to the window and the window is
 * visible and the window receives focus
 *
 * In table format:
 * ```
 * | ----------------------------------------------------------------------------- |
 * | View attached to window | Window visible | Window has focus | Lifecycle state |
 * | ----------------------------------------------------------------------------- |
 * |       not attached      |               Any                 |   INITIALIZED   |
 * | ----------------------------------------------------------------------------- |
 * |                         |  not visible   |       Any        |     CREATED     |
 * |                         ----------------------------------------------------- |
 * |        attached         |                |    not focused   |     STARTED     |
 * |                         |   is visible   |----------------------------------- |
 * |                         |                |    has focus     |     RESUMED     |
 * | ----------------------------------------------------------------------------- |
 * ```
 * ### Notes
 * * [dispose] must be invoked when the [LifecycleOwner] is done and won't be reused
 * * It is always better for [LifecycleOwner] implementations to be more explicit than just
 * listening to the state of the `Window`. E.g. if the code that added the `View` to the `Window`
 * already has access to the correct state to know when that `View` should become visible and when
 * it is ready to receive interaction from the user then it already knows when to move to `STARTED`
 * and `RESUMED`, respectively. In that case, it's better to implement your own `LifecycleOwner`
 * instead of relying on the `Window` callbacks.
 */
class WindowAddedViewLifecycleOwner
@JvmOverloads
constructor(
    private val view: View,
    registryFactory: (LifecycleOwner) -> LifecycleRegistry = { LifecycleRegistry(it) },
) : LifecycleOwner {

    private val windowAttachListener =
        object : ViewTreeObserver.OnWindowAttachListener {
            override fun onWindowAttached() {
                updateCurrentState()
            }

            override fun onWindowDetached() {
                updateCurrentState()
            }
        }
    private val windowFocusListener =
        ViewTreeObserver.OnWindowFocusChangeListener { updateCurrentState() }
    private val windowVisibilityListener =
        ViewTreeObserver.OnWindowVisibilityChangeListener { updateCurrentState() }

    private val registry = registryFactory(this)

    init {
        setCurrentState(Lifecycle.State.INITIALIZED)

        with(view.viewTreeObserver) {
            addOnWindowAttachListener(windowAttachListener)
            addOnWindowVisibilityChangeListener(windowVisibilityListener)
            addOnWindowFocusChangeListener(windowFocusListener)
        }

        updateCurrentState()
    }

    override fun getLifecycle(): Lifecycle {
        return registry
    }

    /**
     * Disposes of this [LifecycleOwner], performing proper clean-up.
     *
     * <p>Invoke this when the instance is finished and won't be reused.
     */
    fun dispose() {
        with(view.viewTreeObserver) {
            removeOnWindowAttachListener(windowAttachListener)
            removeOnWindowVisibilityChangeListener(windowVisibilityListener)
            removeOnWindowFocusChangeListener(windowFocusListener)
        }
    }

    private fun updateCurrentState() {
        val state =
            when {
                !view.isAttachedToWindow -> Lifecycle.State.INITIALIZED
                view.windowVisibility != View.VISIBLE -> Lifecycle.State.CREATED
                !view.hasWindowFocus() -> Lifecycle.State.STARTED
                else -> Lifecycle.State.RESUMED
            }
        setCurrentState(state)
    }

    private fun setCurrentState(state: Lifecycle.State) {
        if (registry.currentState != state) {
            registry.currentState = state
        }
    }
}
