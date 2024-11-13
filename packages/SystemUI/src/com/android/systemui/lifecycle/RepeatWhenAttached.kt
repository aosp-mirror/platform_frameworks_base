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

import android.os.Trace
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.createCoroutineTracingContext
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.systemui.Flags.coroutineTracing
import com.android.systemui.util.Assert
import com.android.systemui.util.Compile
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    val lifecycleCoroutineContext = MAIN_DISPATCHER_SINGLETON + coroutineContext
    val traceName =
        if (Compile.IS_DEBUG && coroutineTracing()) {
            inferTraceSectionName()
        } else {
            DEFAULT_TRACE_NAME
        }
    var lifecycleOwner: ViewLifecycleOwner? = null
    val onAttachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                Assert.isMainThread()
                lifecycleOwner?.onDestroy()
                lifecycleOwner =
                    createLifecycleOwnerAndRun(
                        traceName,
                        view,
                        lifecycleCoroutineContext,
                        block,
                    )
            }

            override fun onViewDetachedFromWindow(v: View) {
                lifecycleOwner?.onDestroy()
                lifecycleOwner = null
            }
        }

    addOnAttachStateChangeListener(onAttachListener)
    if (view.isAttachedToWindow) {
        lifecycleOwner =
            createLifecycleOwnerAndRun(
                traceName,
                view,
                lifecycleCoroutineContext,
                block,
            )
    }

    return DisposableHandle {
        Assert.isMainThread()

        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        view.removeOnAttachStateChangeListener(onAttachListener)
    }
}

private fun createLifecycleOwnerAndRun(
    nameForTrace: String,
    view: View,
    coroutineContext: CoroutineContext,
    block: suspend LifecycleOwner.(View) -> Unit,
): ViewLifecycleOwner {
    return ViewLifecycleOwner(view).apply {
        onCreate()
        lifecycleScope.launch(coroutineContext) { traceCoroutine(nameForTrace) { block(view) } }
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

private fun isFrameInteresting(frame: StackWalker.StackFrame): Boolean =
    frame.className != CURRENT_CLASS_NAME && frame.className != JAVA_ADAPTER_CLASS_NAME

/** Get a name for the trace section include the name of the call site. */
private fun inferTraceSectionName(): String {
    try {
        Trace.traceBegin(Trace.TRACE_TAG_APP, "RepeatWhenAttachedKt#inferTraceSectionName")
        val interestingFrame =
            StackWalker.getInstance().walk { stream ->
                stream.filter(::isFrameInteresting).limit(5).findFirst()
            }
        return if (interestingFrame.isPresent) {
            val f = interestingFrame.get()
            "${f.className}#${f.methodName}:${f.lineNumber} [$DEFAULT_TRACE_NAME]"
        } else {
            DEFAULT_TRACE_NAME
        }
    } finally {
        Trace.traceEnd(Trace.TRACE_TAG_APP)
    }
}

/**
 * Runs the given [block] in a new coroutine when `this` [View]'s Window's [WindowLifecycleState] is
 * at least at [state] (or immediately after calling this function if the window is already at least
 * at [state]), automatically canceling the work when the window is no longer at least at that
 * state.
 *
 * [block] may be run multiple times, running once per every time this` [View]'s Window's
 * [WindowLifecycleState] becomes at least at [state].
 */
suspend fun View.repeatOnWindowLifecycle(
    state: WindowLifecycleState,
    block: suspend CoroutineScope.() -> Unit,
): Nothing {
    when (state) {
        WindowLifecycleState.ATTACHED -> repeatWhenAttachedToWindow(block)
        WindowLifecycleState.VISIBLE -> repeatWhenWindowIsVisible(block)
        WindowLifecycleState.FOCUSED -> repeatWhenWindowHasFocus(block)
    }
}

/**
 * Runs the given [block] every time the [View] becomes attached (or immediately after calling this
 * function, if the view was already attached), automatically canceling the work when the view
 * becomes detached.
 *
 * Only use from the main thread.
 *
 * [block] may be run multiple times, running once per every time the view is attached.
 */
@MainThread
suspend fun View.repeatWhenAttachedToWindow(block: suspend CoroutineScope.() -> Unit): Nothing {
    Assert.isMainThread()
    isAttached.collectLatest { if (it) coroutineScope { block() } }
    awaitCancellation() // satisfies return type of Nothing
}

/**
 * Runs the given [block] every time the [Window] this [View] is attached to becomes visible (or
 * immediately after calling this function, if the window is already visible), automatically
 * canceling the work when the window becomes invisible.
 *
 * Only use from the main thread.
 *
 * [block] may be run multiple times, running once per every time the window becomes visible.
 */
@MainThread
suspend fun View.repeatWhenWindowIsVisible(block: suspend CoroutineScope.() -> Unit): Nothing {
    Assert.isMainThread()
    isWindowVisible.collectLatest { if (it) coroutineScope { block() } }
    awaitCancellation() // satisfies return type of Nothing
}

/**
 * Runs the given [block] every time the [Window] this [View] is attached to has focus (or
 * immediately after calling this function, if the window is already focused), automatically
 * canceling the work when the window loses focus.
 *
 * Only use from the main thread.
 *
 * [block] may be run multiple times, running once per every time the window is focused.
 */
@MainThread
suspend fun View.repeatWhenWindowHasFocus(block: suspend CoroutineScope.() -> Unit): Nothing {
    Assert.isMainThread()
    isWindowFocused.collectLatest { if (it) coroutineScope { block() } }
    awaitCancellation() // satisfies return type of Nothing
}

/** Lifecycle states for a [View]'s interaction with a [android.view.Window]. */
enum class WindowLifecycleState {
    /** Indicates that the [View] is attached to a [android.view.Window]. */
    ATTACHED,
    /**
     * Indicates that the [View] is attached to a [android.view.Window], and the window is visible.
     */
    VISIBLE,
    /**
     * Indicates that the [View] is attached to a [android.view.Window], and the window is visible
     * and focused.
     */
    FOCUSED
}

private val View.isAttached
    get() = conflatedCallbackFlow {
        val onAttachListener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    Assert.isMainThread()
                    trySend(true)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    trySend(false)
                }
            }
        addOnAttachStateChangeListener(onAttachListener)
        trySend(isAttachedToWindow)
        awaitClose { removeOnAttachStateChangeListener(onAttachListener) }
    }

private val View.currentViewTreeObserver: Flow<ViewTreeObserver?>
    get() = isAttached.map { if (it) viewTreeObserver else null }

private val View.isWindowVisible
    get() =
        currentViewTreeObserver.flatMapLatestConflated { vto ->
            vto?.isWindowVisible?.onStart { emit(windowVisibility == View.VISIBLE) } ?: emptyFlow()
        }

private val View.isWindowFocused
    get() =
        currentViewTreeObserver.flatMapLatestConflated { vto ->
            vto?.isWindowFocused?.onStart { emit(hasWindowFocus()) } ?: emptyFlow()
        }

private val ViewTreeObserver.isWindowFocused
    get() = conflatedCallbackFlow {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { trySend(it) }
        addOnWindowFocusChangeListener(listener)
        awaitClose { removeOnWindowFocusChangeListener(listener) }
    }

private val ViewTreeObserver.isWindowVisible
    get() = conflatedCallbackFlow {
        val listener =
            ViewTreeObserver.OnWindowVisibilityChangeListener { v -> trySend(v == View.VISIBLE) }
        addOnWindowVisibilityChangeListener(listener)
        awaitClose { removeOnWindowVisibilityChangeListener(listener) }
    }

/**
 * Even though there is only has one usage of `Dispatchers.Main` in this file, we cache it in a
 * top-level property so that we do not unnecessarily create new `CoroutineContext` objects for
 * tracing on each call to [repeatWhenAttached]. It is okay to reuse a single instance of the
 * tracing context because it is copied for its children.
 *
 * Also, ideally, we would use the injected `@Main CoroutineDispatcher`, but [repeatWhenAttached] is
 * an extension function, and plumbing dagger-injected instances for static usage has little
 * benefit.
 */
private val MAIN_DISPATCHER_SINGLETON =
    Dispatchers.Main + createCoroutineTracingContext("RepeatWhenAttached")
private const val DEFAULT_TRACE_NAME = "repeatWhenAttached"
private const val CURRENT_CLASS_NAME = "com.android.systemui.lifecycle.RepeatWhenAttachedKt"
private const val JAVA_ADAPTER_CLASS_NAME = "com.android.systemui.util.kotlin.JavaAdapterKt"
