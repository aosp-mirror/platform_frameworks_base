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
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.protolog.ShellProtoLogGroup
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
    private val transactionSupplier: () -> SurfaceControl.Transaction,
) : TransitionHandler {

    constructor(
        transitions: Transitions,
        desktopRepository: DesktopRepository,
    ) : this(transitions, desktopRepository, { SurfaceControl.Transaction() })

    private var state: TransitionState? = null

    /** Whether there is an immersive transition that hasn't completed yet. */
    private val inProgress: Boolean
        get() = state != null

    private val rectEvaluator = RectEvaluator()

    /** A listener to invoke on animation changes during entry/exit. */
    var onTaskResizeAnimationListener: OnTaskResizeAnimationListener? = null

    /** Starts a transition to enter full immersive state inside the desktop. */
    fun enterImmersive(taskInfo: RunningTaskInfo, wct: WindowContainerTransaction) {
        if (inProgress) {
            ProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "FullImmersive: cannot start entry because transition already in progress."
            )
            return
        }

        val transition = transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ this)
        state = TransitionState(
            transition = transition,
            displayId = taskInfo.displayId,
            taskId = taskInfo.taskId,
            direction = Direction.ENTER
        )
    }

    fun exitImmersive(taskInfo: RunningTaskInfo, wct: WindowContainerTransaction) {
        if (inProgress) {
            ProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "$TAG: cannot start exit because transition already in progress."
            )
            return
        }

        val transition = transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ this)
        state = TransitionState(
            transition = transition,
            displayId = taskInfo.displayId,
            taskId = taskInfo.taskId,
            direction = Direction.EXIT
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
     * depends in whether the task is in full immersive state or not.
     */
    fun onTransitionReady(transition: IBinder) {
        val state = this.state ?: return
        // TODO: b/369443668 - this assumes invoking the exit transition is the only way to exit
        //  immersive, which isn't realistic. The app could crash, the user could dismiss it from
        //  overview, etc. This (or its caller) should search all transitions to look for any
        //  immersive task exiting that state to keep the repository properly updated.
        if (transition == state.transition) {
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

    /** The state of the currently running transition. */
    private data class TransitionState(
        val transition: IBinder,
        val displayId: Int,
        val taskId: Int,
        val direction: Direction
    )

    private enum class Direction {
        ENTER, EXIT
    }

    private companion object {
        private const val TAG = "FullImmersiveHandler"

        private const val FULL_IMMERSIVE_ANIM_DURATION_MS = 336L
    }
}
