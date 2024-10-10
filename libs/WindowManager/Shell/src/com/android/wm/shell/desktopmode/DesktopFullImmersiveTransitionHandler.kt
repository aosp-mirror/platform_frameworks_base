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
package com.android.wm.shell.desktopmode

import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.animation.DecelerateInterpolator
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.core.animation.addListener
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionHandler
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener

/**
 * A [TransitionHandler] to move a task in/out of desktop's full immersive state where the task
 * remains freeform while being able to take fullscreen bounds and have its App Header visibility
 * be transient below the status bar like in fullscreen immersive mode.
 */
class DesktopFullImmersiveTransitionHandler(
    private val transitions: Transitions,
    private val desktopRepository: DesktopRepository,
    private val displayController: DisplayController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val transactionSupplier: () -> SurfaceControl.Transaction,
) : TransitionHandler {

    constructor(
        transitions: Transitions,
        desktopRepository: DesktopRepository,
        displayController: DisplayController,
        shellTaskOrganizer: ShellTaskOrganizer,
    ) : this(
        transitions,
        desktopRepository,
        displayController,
        shellTaskOrganizer,
        { SurfaceControl.Transaction() }
    )

    private var state: TransitionState? = null

    @VisibleForTesting
    val pendingExternalExitTransitions = mutableSetOf<ExternalPendingExit>()

    /** Whether there is an immersive transition that hasn't completed yet. */
    private val inProgress: Boolean
        get() = state != null

    private val rectEvaluator = RectEvaluator()

    /** A listener to invoke on animation changes during entry/exit. */
    var onTaskResizeAnimationListener: OnTaskResizeAnimationListener? = null

    /** Starts a transition to enter full immersive state inside the desktop. */
    fun moveTaskToImmersive(taskInfo: RunningTaskInfo) {
        if (inProgress) {
            logV("Cannot start entry because transition already in progress.")
            return
        }
        val wct = WindowContainerTransaction().apply {
            setBounds(taskInfo.token, Rect())
        }
        logV("Moving task ${taskInfo.taskId} into immersive mode")
        val transition = transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ this)
        state = TransitionState(
            transition = transition,
            displayId = taskInfo.displayId,
            taskId = taskInfo.taskId,
            direction = Direction.ENTER
        )
    }

    fun moveTaskToNonImmersive(taskInfo: RunningTaskInfo) {
        if (inProgress) {
            logV("Cannot start exit because transition already in progress.")
            return
        }

        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return
        val destinationBounds = calculateMaximizeBounds(displayLayout, taskInfo)
        val wct = WindowContainerTransaction().apply {
            setBounds(taskInfo.token, destinationBounds)
        }
        logV("Moving task ${taskInfo.taskId} out of immersive mode")
        val transition = transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ this)
        state = TransitionState(
            transition = transition,
            displayId = taskInfo.displayId,
            taskId = taskInfo.taskId,
            direction = Direction.EXIT
        )
    }

    /**
     * Bring the immersive app of the given [displayId] out of immersive mode, if applicable.
     *
     * @param transition that will apply this transaction
     * @param wct that will apply these changes
     * @param displayId of the display that should exit immersive mode
     */
    fun exitImmersiveIfApplicable(
        transition: IBinder,
        wct: WindowContainerTransaction,
        displayId: Int
    ) {
        if (!Flags.enableFullyImmersiveInDesktop()) return
        exitImmersiveIfApplicable(wct, displayId)?.invoke(transition)
    }

    /**
     * Bring the immersive app of the given [displayId] out of immersive mode, if applicable.
     *
     * @param wct that will apply these changes
     * @param displayId of the display that should exit immersive mode
     * @return a function to apply once the transition that will apply these changes is started
     */
    fun exitImmersiveIfApplicable(
        wct: WindowContainerTransaction,
        displayId: Int
    ): ((IBinder) -> Unit)? {
        if (!Flags.enableFullyImmersiveInDesktop()) return null
        val displayLayout = displayController.getDisplayLayout(displayId) ?: return null
        val immersiveTask = desktopRepository.getTaskInFullImmersiveState(displayId) ?: return null
        val taskInfo = shellTaskOrganizer.getRunningTaskInfo(immersiveTask) ?: return null
        logV("Appending immersive exit for task: $immersiveTask in display: $displayId")
        wct.setBounds(taskInfo.token, calculateMaximizeBounds(displayLayout, taskInfo))
        return { transition -> addPendingImmersiveExit(immersiveTask, displayId, transition) }
    }

    /**
     * Bring the given [taskInfo] out of immersive mode, if applicable.
     *
     * @param wct that will apply these changes
     * @param taskInfo of the task that should exit immersive mode
     * @return a function to apply once the transition that will apply these changes is started
     */
    fun exitImmersiveIfApplicable(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ): ((IBinder) -> Unit)? {
        if (!Flags.enableFullyImmersiveInDesktop()) return null
        if (desktopRepository.isTaskInFullImmersiveState(taskInfo.taskId)) {
            // A full immersive task is being minimized, make sure the immersive state is broken
            // (i.e. resize back to max bounds).
            displayController.getDisplayLayout(taskInfo.displayId)?.let { displayLayout ->
                wct.setBounds(taskInfo.token, calculateMaximizeBounds(displayLayout, taskInfo))
                logV("Appending immersive exit for task: ${taskInfo.taskId}")
                return { transition ->
                    addPendingImmersiveExit(
                        taskId = taskInfo.taskId,
                        displayId = taskInfo.displayId,
                        transition = transition
                    )
                }
            }
        }
        return null
    }

    private fun addPendingImmersiveExit(taskId: Int, displayId: Int, transition: IBinder) {
        pendingExternalExitTransitions.add(
            ExternalPendingExit(
                taskId = taskId,
                displayId = displayId,
                transition = transition
            )
        )
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ): Boolean {
        val state = requireState()
        if (transition != state.transition) return false
        animateResize(
            transitionState = state,
            info = info,
            startTransaction = startTransaction,
            finishTransaction = finishTransaction,
            finishCallback = finishCallback
        )
        return true
    }

    private fun animateResize(
        transitionState: TransitionState,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ) {
        val change = info.changes.first { c ->
            val taskInfo = c.taskInfo
            return@first taskInfo != null && taskInfo.taskId == transitionState.taskId
        }
        val leash = change.leash
        val startBounds = change.startAbsBounds
        val endBounds = change.endAbsBounds

        val updateTransaction = transactionSupplier()
        ValueAnimator.ofObject(rectEvaluator, startBounds, endBounds).apply {
            duration = FULL_IMMERSIVE_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(
                onStart = {
                    startTransaction
                        .setPosition(leash, startBounds.left.toFloat(), startBounds.top.toFloat())
                        .setWindowCrop(leash, startBounds.width(), startBounds.height())
                        .show(leash)
                    onTaskResizeAnimationListener
                        ?.onAnimationStart(transitionState.taskId, startTransaction, startBounds)
                        ?: startTransaction.apply()
                },
                onEnd = {
                    finishTransaction
                        .setPosition(leash, endBounds.left.toFloat(), endBounds.top.toFloat())
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                        .apply()
                    onTaskResizeAnimationListener?.onAnimationEnd(transitionState.taskId)
                    finishCallback.onTransitionFinished(null /* wct */)
                    clearState()
                }
            )
            addUpdateListener { animation ->
                val rect = animation.animatedValue as Rect
                updateTransaction
                    .setPosition(leash, rect.left.toFloat(), rect.top.toFloat())
                    .setWindowCrop(leash, rect.width(), rect.height())
                    .apply()
                onTaskResizeAnimationListener
                    ?.onBoundsChange(transitionState.taskId, updateTransaction, rect)
                    ?: updateTransaction.apply()
            }
            start()
        }
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? = null

    override fun onTransitionConsumed(
        transition: IBinder,
        aborted: Boolean,
        finishTransaction: SurfaceControl.Transaction?
    ) {
        val state = this.state ?: return
        if (transition == state.transition && aborted) {
            clearState()
        }
        super.onTransitionConsumed(transition, aborted, finishTransaction)
    }

    /**
     * Called when any transition in the system is ready to play. This is needed to update the
     * repository state before window decorations are drawn (which happens immediately after
     * |onTransitionReady|, before this transition actually animates) because drawing decorations
     * depends on whether the task is in full immersive state or not.
     */
    fun onTransitionReady(transition: IBinder, info: TransitionInfo) {
        // Check if this is a pending external exit transition.
        val pendingExit = pendingExternalExitTransitions
            .firstOrNull { pendingExit -> pendingExit.transition == transition }
        if (pendingExit != null) {
            pendingExternalExitTransitions.remove(pendingExit)
            if (info.hasTaskChange(taskId = pendingExit.taskId)) {
                if (desktopRepository.isTaskInFullImmersiveState(pendingExit.taskId)) {
                    logV("Pending external exit for task ${pendingExit.taskId} verified")
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = pendingExit.displayId,
                        taskId = pendingExit.taskId,
                        immersive = false
                    )
                }
            }
            return
        }

        // Check if this is a direct immersive enter/exit transition.
        val state = this.state ?: return
        if (transition == state.transition) {
            logV("Direct move for task ${state.taskId} in ${state.direction} direction verified")
            when (state.direction) {
                Direction.ENTER -> {
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = state.displayId,
                        taskId = state.taskId,
                        immersive = true
                    )
                }
                Direction.EXIT -> {
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = state.displayId,
                        taskId = state.taskId,
                        immersive = false
                    )
                }
            }
        }
    }

    private fun clearState() {
        state = null
    }

    private fun requireState(): TransitionState =
        state ?: error("Expected non-null transition state")

    private fun TransitionInfo.hasTaskChange(taskId: Int): Boolean =
        changes.any { c -> c.taskInfo?.taskId == taskId }

    /** The state of the currently running transition. */
    private data class TransitionState(
        val transition: IBinder,
        val displayId: Int,
        val taskId: Int,
        val direction: Direction
    )

    /**
     * Tracks state of a transition involving an immersive exit that is external to this class' own
     * transitions. This usually means transitions that exit immersive mode as a side-effect and
     * not the primary action (for example, minimizing the immersive task or launching a new task
     * on top of the immersive task).
     */
    data class ExternalPendingExit(
        val taskId: Int,
        val displayId: Int,
        val transition: IBinder,
    )

    private enum class Direction {
        ENTER, EXIT
    }

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private companion object {
        private const val TAG = "DesktopImmersive"

        private const val FULL_IMMERSIVE_ANIM_DURATION_MS = 336L
    }
}
