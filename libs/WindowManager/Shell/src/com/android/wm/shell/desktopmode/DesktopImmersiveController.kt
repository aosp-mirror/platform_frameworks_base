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
import android.window.DesktopModeFlags.ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
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
import com.android.wm.shell.transition.Transitions.TransitionObserver
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener

/**
 * A controller to move tasks in/out of desktop's full immersive state where the task
 * remains freeform while being able to take fullscreen bounds and have its App Header visibility
 * be transient below the status bar like in fullscreen immersive mode.
 */
class DesktopImmersiveController(
    private val transitions: Transitions,
    private val desktopRepository: DesktopRepository,
    private val displayController: DisplayController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val transactionSupplier: () -> SurfaceControl.Transaction,
) : TransitionHandler, TransitionObserver {

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
    val pendingExternalExitTransitions = mutableListOf<ExternalPendingExit>()

    /** Whether there is an immersive transition that hasn't completed yet. */
    private val inProgress: Boolean
        get() = state != null || pendingExternalExitTransitions.isNotEmpty()

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

        val wct = WindowContainerTransaction().apply {
            setBounds(taskInfo.token, getExitDestinationBounds(taskInfo))
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
        val result = exitImmersiveIfApplicable(wct, displayId)
        result.asExit()?.runOnTransitionStart?.invoke(transition)
    }

    /**
     * Bring the immersive app of the given [displayId] out of immersive mode, if applicable.
     *
     * @param wct that will apply these changes
     * @param displayId of the display that should exit immersive mode
     * @param excludeTaskId of the task to ignore (not exit) if it is the immersive one
     * @return a function to apply once the transition that will apply these changes is started
     */
    fun exitImmersiveIfApplicable(
        wct: WindowContainerTransaction,
        displayId: Int,
        excludeTaskId: Int? = null,
    ): ExitResult {
        if (!Flags.enableFullyImmersiveInDesktop()) return ExitResult.NoExit
        val immersiveTask = desktopRepository.getTaskInFullImmersiveState(displayId)
            ?: return ExitResult.NoExit
        if (immersiveTask == excludeTaskId) {
            return ExitResult.NoExit
        }
        val taskInfo = shellTaskOrganizer.getRunningTaskInfo(immersiveTask)
            ?: return ExitResult.NoExit
        logV("Appending immersive exit for task: $immersiveTask in display: $displayId")
        wct.setBounds(taskInfo.token, getExitDestinationBounds(taskInfo))
        return ExitResult.Exit(
            exitingTask = immersiveTask,
            runOnTransitionStart = { transition ->
                addPendingImmersiveExit(immersiveTask, displayId, transition)
            }
        )
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
    ): ExitResult {
        if (!Flags.enableFullyImmersiveInDesktop()) return ExitResult.NoExit
        if (desktopRepository.isTaskInFullImmersiveState(taskInfo.taskId)) {
            // A full immersive task is being minimized, make sure the immersive state is broken
            // (i.e. resize back to max bounds).
            wct.setBounds(taskInfo.token, getExitDestinationBounds(taskInfo))
            logV("Appending immersive exit for task: ${taskInfo.taskId}")
            return ExitResult.Exit(
                exitingTask = taskInfo.taskId,
                runOnTransitionStart = { transition ->
                    addPendingImmersiveExit(
                        taskId = taskInfo.taskId,
                        displayId = taskInfo.displayId,
                        transition = transition
                    )
                }
            )
        }
        return ExitResult.NoExit
    }


    /** Whether the [change] in the [transition] is a known immersive change. */
    fun isImmersiveChange(
        transition: IBinder,
        change: TransitionInfo.Change,
    ): Boolean {
        return pendingExternalExitTransitions.any {
            it.transition == transition && it.taskId == change.taskInfo?.taskId
        }
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
        logD("startAnimation transition=%s", transition)
        animateResize(
            targetTaskId = state.taskId,
            info = info,
            startTransaction = startTransaction,
            finishTransaction = finishTransaction,
            finishCallback = finishCallback
        )
        return true
    }

    private fun animateResize(
        targetTaskId: Int,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ) {
        logD("animateResize for task#%d", targetTaskId)
        val change = info.changes.first { c ->
            val taskInfo = c.taskInfo
            return@first taskInfo != null && taskInfo.taskId == targetTaskId
        }
        animateResizeChange(change, startTransaction, finishTransaction, finishCallback)
    }

    /**
     *  Animate an immersive change.
     *
     *  As of now, both enter and exit transitions have the same animation, a veiled resize.
     */
    fun animateResizeChange(
        change: TransitionInfo.Change,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
    ) {
        val taskId = change.taskInfo!!.taskId
        val leash = change.leash
        val startBounds = change.startAbsBounds
        val endBounds = change.endAbsBounds
        logD("Animating resize change for task#%d from %s to %s", taskId, startBounds, endBounds)

        startTransaction
            .setPosition(leash, startBounds.left.toFloat(), startBounds.top.toFloat())
            .setWindowCrop(leash, startBounds.width(), startBounds.height())
            .show(leash)
        onTaskResizeAnimationListener
            ?.onAnimationStart(taskId, startTransaction, startBounds)
            ?: startTransaction.apply()
        val updateTransaction = transactionSupplier()
        ValueAnimator.ofObject(rectEvaluator, startBounds, endBounds).apply {
            duration = FULL_IMMERSIVE_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(
                onEnd = {
                    finishTransaction
                        .setPosition(leash, endBounds.left.toFloat(), endBounds.top.toFloat())
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                        .apply()
                    onTaskResizeAnimationListener?.onAnimationEnd(taskId)
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
                    ?.onBoundsChange(taskId, updateTransaction, rect)
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
    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
    ) {
        // Check if this is a pending external exit transition.
        val pendingExit = pendingExternalExitTransitions
            .firstOrNull { pendingExit -> pendingExit.transition == transition }
        if (pendingExit != null) {
            if (info.hasTaskChange(taskId = pendingExit.taskId)) {
                if (desktopRepository.isTaskInFullImmersiveState(pendingExit.taskId)) {
                    logV("Pending external exit for task#%d verified", pendingExit.taskId)
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = pendingExit.displayId,
                        taskId = pendingExit.taskId,
                        immersive = false
                    )
                    if (Flags.enableRestoreToPreviousSizeFromDesktopImmersive()) {
                        desktopRepository.removeBoundsBeforeFullImmersive(pendingExit.taskId)
                    }
                }
            }
            return
        }

        // Check if this is a direct immersive enter/exit transition.
        if (transition == state?.transition) {
            val state = requireState()
            val startBounds = info.changes.first { c -> c.taskInfo?.taskId == state.taskId }
                .startAbsBounds
            logV("Direct move for task#%d in %s direction verified", state.taskId, state.direction)
            when (state.direction) {
                Direction.ENTER -> {
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = state.displayId,
                        taskId = state.taskId,
                        immersive = true
                    )
                    if (Flags.enableRestoreToPreviousSizeFromDesktopImmersive()) {
                        desktopRepository.saveBoundsBeforeFullImmersive(state.taskId, startBounds)
                    }
                }
                Direction.EXIT -> {
                    desktopRepository.setTaskInFullImmersiveState(
                        displayId = state.displayId,
                        taskId = state.taskId,
                        immersive = false
                    )
                    if (Flags.enableRestoreToPreviousSizeFromDesktopImmersive()) {
                        desktopRepository.removeBoundsBeforeFullImmersive(state.taskId)
                    }
                }
            }
            return
        }

        // Check if this is an untracked exit transition, like display rotation.
        info.changes
            .filter { c -> c.taskInfo != null }
            .filter { c -> desktopRepository.isTaskInFullImmersiveState(c.taskInfo!!.taskId) }
            .filter { c -> c.startRotation != c.endRotation }
            .forEach { c ->
                logV("Detected immersive exit due to rotation for task#%d", c.taskInfo!!.taskId)
                desktopRepository.setTaskInFullImmersiveState(
                    displayId = c.taskInfo!!.displayId,
                    taskId = c.taskInfo!!.taskId,
                    immersive = false
                )
            }
    }

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
        val pendingExit = pendingExternalExitTransitions
            .firstOrNull { pendingExit -> pendingExit.transition == merged }
        if (pendingExit != null) {
            logV(
                "Pending exit transition %s for task#%s merged into %s",
                merged, pendingExit.taskId, playing
            )
            pendingExit.transition = playing
        }
    }

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
        val pendingExit = pendingExternalExitTransitions
            .firstOrNull { pendingExit -> pendingExit.transition == transition }
        if (pendingExit != null) {
            logV(
                "Pending exit transition %s for task#%s finished",
                transition, pendingExit
            )
            pendingExternalExitTransitions.remove(pendingExit)
        }
    }

    private fun clearState() {
        state = null
    }

    private fun getExitDestinationBounds(taskInfo: RunningTaskInfo): Rect {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId)
            ?: error("Expected non-null display layout for displayId: ${taskInfo.displayId}")
        return if (Flags.enableRestoreToPreviousSizeFromDesktopImmersive()) {
            desktopRepository.removeBoundsBeforeFullImmersive(taskInfo.taskId)
                ?: if (ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS.isTrue()) {
                    calculateInitialBounds(displayLayout, taskInfo)
                } else {
                    calculateDefaultDesktopTaskBounds(displayLayout)
                }
        } else {
            return calculateMaximizeBounds(displayLayout, taskInfo)
        }
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
        var transition: IBinder,
    )

    /** The result of an external exit request. */
    sealed class ExitResult {
        /** An immersive task exit (meaning, resize) was appended to the request. */
        data class Exit(
            val exitingTask: Int,
            val runOnTransitionStart: ((IBinder) -> Unit)
        ) : ExitResult()
        /** There was no exit appended to the request. */
        data object NoExit : ExitResult()

        /** Returns the result as an [Exit] or null if it isn't of that type. */
        fun asExit(): Exit? = if (this is Exit) this else null
    }

    private enum class Direction {
        ENTER, EXIT
    }

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private fun logD(msg: String, vararg arguments: Any?) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private companion object {
        private const val TAG = "DesktopImmersive"

        private const val FULL_IMMERSIVE_ANIM_DURATION_MS = 336L
    }
}
