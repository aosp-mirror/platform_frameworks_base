/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.lifecycle

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import androidx.collection.MutableScatterSet
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.core.os.HandlerCompat
import com.android.systemui.res.R

/**
 * [SnapshotViewBindingRoot] is installed on the root view of an attached view hierarchy and
 * coordinates all [SnapshotViewBinding]s for the window.
 *
 * This class is not thread-safe. It should only be accessed from the thread corresponding to the UI
 * thread referenced by the [handler] and [choreographer] constructor parameters. These two
 * parameters must refer to the same UI thread.
 *
 * Lazily created and installed on a root attached view by [bindingRoot].
 */
private class SnapshotViewBindingRoot(
    private val handler: Handler,
    private val choreographer: Choreographer
) {
    /** Multiplexer for all snapshot state observations; see [start] and [stop] */
    private val observer = SnapshotStateObserver { task ->
        if (Looper.myLooper() === handler.looper) task() else handler.post(task)
    }

    /** `true` if a [Choreographer] frame is currently scheduled */
    private var isFrameScheduled = false

    /**
     * Unordered set of [SnapshotViewBinding]s that have been invalidated and are awaiting handling
     * by an upcoming frame.
     */
    private val invalidatedBindings = MutableScatterSet<SnapshotViewBinding>()

    /**
     * Callback for [SnapshotStateObserver.observeReads] allocated once for the life of the
     * [SnapshotViewBindingRoot] and reused to avoid extra allocations during frame operations.
     */
    private val onBindingChanged: (SnapshotViewBinding) -> Unit = {
        invalidatedBindings += it
        if (!isFrameScheduled) {
            choreographer.postFrameCallback(frameCallback)
            isFrameScheduled = true
        }
    }

    /** Callback for [Choreographer.postFrameCallback] */
    private val frameCallback =
        Choreographer.FrameCallback {
            try {
                bindInvalidatedBindings()
            } finally {
                isFrameScheduled = false
            }
        }

    /**
     * Perform binding of all [SnapshotViewBinding]s in [invalidatedBindings] within a single
     * mutable snapshot. The snapshot will be committed if no exceptions are thrown from any
     * binding's `onError` handler.
     */
    private fun bindInvalidatedBindings() {
        Snapshot.withMutableSnapshot {
            // removeIf is used here to perform a forEach where each element is removed
            // as the invalid bindings are traversed. If a performBindOf throws we want
            // the rest of the unhandled invalidations to remain.
            invalidatedBindings.removeIf { binding ->
                performBindOf(binding)
                true
            }
        }
    }

    /**
     * Perform the view binding for [binding] while observing its snapshot reads. Once this method
     * is called for a [binding] this [SnapshotViewBindingRoot] may retain hard references back to
     * [binding] via [observer], [invalidatedBindings] or both. Use [forgetBinding] to drop these
     * references once a [SnapshotViewBinding] is no longer relevant.
     *
     * This method should only be called after [start] has been called and before [stop] has been
     * called; failing to obey this constraint may result in lingering hard references to [binding]
     * or missed invalidations in response to snapshot state that was changed prior to [start] being
     * called.
     */
    fun performBindOf(binding: SnapshotViewBinding) {
        try {
            observer.observeReads(binding, onBindingChanged, binding.performBind)
        } catch (error: Throwable) {
            // Note: it is valid (and the default) for this call to re-throw the error
            binding.onError(error)
        }
    }

    /**
     * Forget about [binding], dropping all observed tracking and invalidation state. After calling
     * this method it is safe to abandon [binding] to the garbage collector.
     */
    fun forgetBinding(binding: SnapshotViewBinding) {
        observer.clear(binding)
        invalidatedBindings.remove(binding)
    }

    /**
     * Start tracking snapshot commits that may affect [SnapshotViewBinding]s passed to
     * [performBindOf] calls. Call this method before invoking [performBindOf].
     *
     * Once this method has been called, [stop] must be called prior to abandoning this
     * [SnapshotViewBindingRoot] to the garbage collector, as a hard reference to it will be
     * retained by the snapshot system until [stop] is invoked.
     */
    fun start() {
        observer.start()
    }

    /**
     * Stop tracking snapshot commits that may affect [SnapshotViewBinding]s that have been passed
     * to [performBindOf], cancel any pending [choreographer] frame callback, and forget all
     * [invalidatedBindings].
     *
     * Call [stop] prior to abandoning this [SnapshotViewBindingRoot] to the garbage collector.
     *
     * Calling [start] again after [stop] will begin tracking invalidations again, but any
     * [SnapshotViewBinding]s must be re-bound using [performBindOf] after the [start] call returns.
     */
    fun stop() {
        observer.stop()
        choreographer.removeFrameCallback(frameCallback)
        isFrameScheduled = false
        invalidatedBindings.clear()
    }
}

/**
 * Return the [SnapshotViewBindingRoot] for this [View], lazily creating it if it does not yet
 * exist. This [View] must be currently attached to a window and this property should only be
 * accessed from this [View]'s UI thread.
 *
 * The [SnapshotViewBindingRoot] will be [started][SnapshotViewBindingRoot.start] before this
 * property get returns, making it safe to call [SnapshotViewBindingRoot.performBindOf] for the
 * [bindingRoot] of an attached [View].
 *
 * When the [View] becomes attached to a window the [SnapshotViewBindingRoot] will automatically be
 * [started][SnapshotViewBindingRoot.start]. When it becomes detached from its window it will
 * automatically be [stopped][SnapshotViewBindingRoot.stop].
 *
 * This should generally only be called on the [View] returned by [View.getRootView] for an attached
 * [View].
 */
private val View.bindingRoot: SnapshotViewBindingRoot
    get() {
        val tag = getTag(R.id.snapshot_view_binding_root) as? SnapshotViewBindingRoot
        if (tag != null) return tag
        val newRoot =
            SnapshotViewBindingRoot(
                // Use an async handler for processing invalidations; this ensures invalidations
                // are tracked for the upcoming frame and not the next frame.
                handler =
                    HandlerCompat.createAsync(
                        handler?.looper ?: error("$this is not attached to a window")
                    ),
                choreographer = Choreographer.getInstance()
            )
        setTag(R.id.snapshot_view_binding_root, newRoot)
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    newRoot.start()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    newRoot.stop()
                }
            }
        )
        if (isAttachedToWindow) newRoot.start()
        return newRoot
    }

/**
 * A single [SnapshotViewBinding] set on a [View] by [setSnapshotBinding]. The [SnapshotViewBinding]
 * is responsible for invoking [SnapshotViewBindingRoot.performBindOf] when the associated [View]
 * becomes attached to a window in order to register it for invalidation tracking and rebinding as
 * relevant snapshot state changes. When the [View] becomes detached the binding will invoke
 * [SnapshotViewBindingRoot.forgetBinding] for itself.
 */
private class SnapshotViewBinding(
    val performBind: () -> Unit,
    val onError: (Throwable) -> Unit,
) : View.OnAttachStateChangeListener {

    override fun onViewAttachedToWindow(view: View) {
        Snapshot.withMutableSnapshot { view.rootView.bindingRoot.performBindOf(this) }
    }

    override fun onViewDetachedFromWindow(view: View) {
        view.rootView.bindingRoot.forgetBinding(this)
    }
}

/**
 * Set binding logic for this [View] that will be re-invoked for UI frames where relevant [Snapshot]
 * state has changed. This can be especially useful for codebases with mixed usage of both Views and
 * [Jetpack Compose](https://d.android.com/compose), enabling the same patterns of snapshot-backed
 * state management when using either UI toolkit.
 *
 * In the following example the sender name and message text of a message item view will be kept up
 * to date with the snapshot-backed `model.senderName` and `model.messageText` properties:
 * ```
 * val view = layoutInflater.inflate(R.layout.single_message, parent, false)
 * val senderNameView = view.findViewById<TextView>(R.id.sender_name)
 * val messageTextView = view.findViewById<TextView>(R.id.message_text)
 * view.setSnapshotBinding {
 *     senderNameView.text = model.senderName
 *     messageTextView.text = model.messageText
 * }
 * ```
 *
 * Snapshot binding may also be used in concert with
 * [View binding](https://developer.android.com/topic/libraries/view-binding):
 * ```
 * val binding = SingleMessageBinding.inflate(layoutInflater)
 * binding.root.setSnapshotBinding {
 *     binding.senderName.text = model.senderName
 *     binding.messageText.text = model.messageText
 * }
 * ```
 *
 * When a snapshot binding is set [performBind] will be invoked immediately before
 * [setSnapshotBinding] returns if this [View] is currently attached to a window. If the view is not
 * currently attached, [performBind] will be invoked when the view becomes attached to a window.
 *
 * If a snapshot commit changes state accessed by [performBind] changes while the view remains
 * attached to its window and the snapshot binding is not replaced or [cleared][clearBinding], the
 * binding will be considered _invalidated,_ a rebinding will be scheduled for the upcoming UI
 * frame, and [performBind] will be re-executed prior to the layout and draw phases for the frame.
 * [performBind] will only be re-executed **once** for any given UI frame provided that
 * [setSnapshotBinding] is not called again.
 *
 * [performBind] is always invoked from a [mutable snapshot][Snapshot.takeMutableSnapshot], ensuring
 * atomic consistency of all snapshot state reads within it. **All** rebinding performed for
 * invalidations of bindings within the same window for a given UI frame are performed within the
 * **same** snapshot, ensuring that same atomic consistency of snapshot state for **all** snapshot
 * bindings within the same window.
 *
 * As [performBind] is invoked for rebinding as part of the UI frame itself, [performBind]
 * implementations should be both fast and idempotent to avoid delaying the UI frame.
 *
 * There are no mutual ordering guarantees between separate snapshot bindings; the [performBind] of
 * separate snapshot bindings may be executed in any order. Similarly, no ordering guarantees exist
 * between snapshot binding rebinding and Jetpack Compose recomposition. Snapshot bindings and
 * Compose UIs both should obey
 * [unidirectional data flow](https://developer.android.com/topic/architecture/ui-layer#udf)
 * principles, consuming state from mutual single sources of truth and avoid consuming state
 * produced by the rebinding or recomposition of other UI components.
 */
fun View.setSnapshotBinding(onError: (Throwable) -> Unit = { throw it }, performBind: () -> Unit) {
    clearBinding()
    val newBinding = SnapshotViewBinding(performBind, onError)
    setTag(R.id.snapshot_view_binding, newBinding)
    addOnAttachStateChangeListener(newBinding)
    if (isAttachedToWindow) newBinding.onViewAttachedToWindow(this)
}

/**
 * Remove a snapshot binding that was set by [setSnapshotBinding]. It is not necessary to call this
 * function before abandoning a [View] with a snapshot binding to the garbage collector.
 */
fun View.clearBinding() {
    val oldBinding = getTag(R.id.snapshot_view_binding) as? SnapshotViewBinding
    if (oldBinding != null) {
        removeOnAttachStateChangeListener(oldBinding)
        if (isAttachedToWindow) {
            oldBinding.onViewDetachedFromWindow(this)
        }
    }
}
