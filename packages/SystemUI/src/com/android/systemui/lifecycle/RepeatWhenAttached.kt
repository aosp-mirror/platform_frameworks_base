/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.lifecycle

import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.android.systemui.util.Assert
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/**
 * Runs the given [block] every time the [View] becomes attached (or immediately after calling this
 * function, if the view was already attached), automatically canceling the work when the `View`
 * becomes detached.
 *
 * Only use from the main thread.
 *
 * When [block] is run, it is run in the context of a [ViewLifecycleOwner] which the caller can use
 * to launch jobs, with confidence that the jobs will be properly canceled when the view is
 * detached.
 *
 * The [block] may be run multiple times, running once per every time the view is attached. Each
 * time the block is run for a new attachment event, the [ViewLifecycleOwner] provided will be a
 * fresh one.
 *
 * @param coroutineContext An optional [CoroutineContext] to replace the dispatcher [block] is
 *   invoked on.
 * @param block The block of code that should be run when the view becomes attached. It can end up
 *   being invoked multiple times if the view is reattached after being detached.
 * @return A [DisposableHandle] to invoke when the caller of the function destroys its [View] and is
 *   no longer interested in the [block] being run the next time its attached. Calling this is an
 *   optional optimization as the logic will be properly cleaned up and destroyed each time the view
 *   is detached. Using this is not *thread-safe* and should only be used on the main thread.
 */
@MainThread
fun View.repeatWhenAttached(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    block: suspend LifecycleOwner.(View) -> Unit,
): DisposableHandle {
    Assert.isMainThread()
    val view = this
    // The suspend block will run on the app's main thread unless the caller supplies a different
    // dispatcher to use. We don't want it to run on the Dispatchers.Default thread pool as
    // default behavior. Instead, we want it to run on the view's UI thread since the user will
    // presumably want to call view methods that require being called from said UI thread.
    val lifecycleCoroutineContext = Dispatchers.Main + coroutineContext
    var lifecycleOwner: ViewLifecycleOwner? = null
    val onAttachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                Assert.isMainThread()
                lifecycleOwner?.onDestroy()
                lifecycleOwner =
                    createLifecycleOwnerAndRun(
                        view,
                        lifecycleCoroutineContext,
                        block,
                    )
            }

            override fun onViewDetachedFromWindow(v: View?) {
                lifecycleOwner?.onDestroy()
                lifecycleOwner = null
            }
        }

    addOnAttachStateChangeListener(onAttachListener)
    if (view.isAttachedToWindow) {
        lifecycleOwner =
            createLifecycleOwnerAndRun(
                view,
                lifecycleCoroutineContext,
                block,
            )
    }

    return object : DisposableHandle {
        override fun dispose() {
            Assert.isMainThread()

            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
            view.removeOnAttachStateChangeListener(onAttachListener)
        }
    }
}

private fun createLifecycleOwnerAndRun(
    view: View,
    coroutineContext: CoroutineContext,
    block: suspend LifecycleOwner.(View) -> Unit,
): ViewLifecycleOwner {
    return ViewLifecycleOwner(view).apply {
        onCreate()
        lifecycleScope.launch(coroutineContext) { block(view) }
    }
}

/**
 * A [LifecycleOwner] for a [View] for exclusive use by the [repeatWhenAttached] extension function.
 *
 * The implementation requires the caller to call [onCreate] and [onDestroy] when the view is
 * attached to or detached from a view hierarchy. After [onCreate] and before [onDestroy] is called,
 * the implementation monitors window state in the following way
 * * If the window is not visible, we are in the [Lifecycle.State.CREATED] state
 * * If the window is visible but not focused, we are in the [Lifecycle.State.STARTED] state
 * * If the window is visible and focused, we are in the [Lifecycle.State.RESUMED] state
 *
 * Or in table format:
 * ```
 * ┌───────────────┬───────────────────┬──────────────┬─────────────────┐
 * │ View attached │ Window Visibility │ Window Focus │ Lifecycle State │
 * ├───────────────┼───────────────────┴──────────────┼─────────────────┤
 * │ Not attached  │                 Any              │       N/A       │
 * ├───────────────┼───────────────────┬──────────────┼─────────────────┤
 * │               │    Not visible    │     Any      │     CREATED     │
 * │               ├───────────────────┼──────────────┼─────────────────┤
 * │   Attached    │                   │   No focus   │     STARTED     │
 * │               │      Visible      ├──────────────┼─────────────────┤
 * │               │                   │  Has focus   │     RESUMED     │
 * └───────────────┴───────────────────┴──────────────┴─────────────────┘
 * ```
 */
class ViewLifecycleOwner(
    private val view: View,
) : LifecycleOwner {

    private val windowVisibleListener =
        ViewTreeObserver.OnWindowVisibilityChangeListener { updateState() }
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { updateState() }

    private val registry = LifecycleRegistry(this)

    fun onCreate() {
        registry.currentState = Lifecycle.State.CREATED
        view.viewTreeObserver.addOnWindowVisibilityChangeListener(windowVisibleListener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        updateState()
    }

    fun onDestroy() {
        view.viewTreeObserver.removeOnWindowVisibilityChangeListener(windowVisibleListener)
        view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle
        get() {
            return registry
        }

    private fun updateState() {
        registry.currentState =
            when {
                view.windowVisibility != View.VISIBLE -> Lifecycle.State.CREATED
                !view.hasWindowFocus() -> Lifecycle.State.STARTED
                else -> Lifecycle.State.RESUMED
            }
    }
}
