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

import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.DesktopModeFlags
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.MixedTransitionHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback

/** The [Transitions.TransitionHandler] coordinates transition handlers in desktop windowing. */
class DesktopMixedTransitionHandler(
    private val context: Context,
    private val transitions: Transitions,
    private val desktopRepository: DesktopRepository,
    private val freeformTaskTransitionHandler: FreeformTaskTransitionHandler,
    private val closeDesktopTaskTransitionHandler: CloseDesktopTaskTransitionHandler,
    private val desktopImmersiveController: DesktopImmersiveController,
    private val interactionJankMonitor: InteractionJankMonitor,
    @ShellMainThread private val handler: Handler,
) : MixedTransitionHandler, FreeformTaskTransitionStarter {

    @VisibleForTesting
    val pendingMixedTransitions = mutableListOf<PendingMixedTransition>()

    /** Delegates starting transition to [FreeformTaskTransitionHandler]. */
    override fun startWindowingModeTransition(
        targetWindowingMode: Int,
        wct: WindowContainerTransaction?,
    ) = freeformTaskTransitionHandler.startWindowingModeTransition(targetWindowingMode, wct)

    /** Delegates starting minimized mode transition to [FreeformTaskTransitionHandler]. */
    override fun startMinimizedModeTransition(wct: WindowContainerTransaction?): IBinder =
        freeformTaskTransitionHandler.startMinimizedModeTransition(wct)

    /** Starts close transition and handles or delegates desktop task close animation. */
    override fun startRemoveTransition(wct: WindowContainerTransaction?): IBinder {
        if (!DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS.isTrue) {
            return freeformTaskTransitionHandler.startRemoveTransition(wct)
        }
        requireNotNull(wct)
        return transitions.startTransition(WindowManager.TRANSIT_CLOSE, wct, /* handler= */ this)
            .also { transition ->
                pendingMixedTransitions.add(PendingMixedTransition.Close(transition))
            }
    }

    /**
     * Starts a launch transition for [taskId], with an optional [exitingImmersiveTask] if it was
     * included in the [wct] and is expected to be animated by this handler.
     */
    fun startLaunchTransition(
        @WindowManager.TransitionType transitionType: Int,
        wct: WindowContainerTransaction,
        taskId: Int,
        exitingImmersiveTask: Int? = null,
    ): IBinder {
        if (!Flags.enableFullyImmersiveInDesktop()) {
            return transitions.startTransition(transitionType, wct, /* handler= */ null)
        }
        if (exitingImmersiveTask == null) {
            logV("Starting mixed launch transition for task#%d", taskId)
        } else {
            logV(
                "Starting mixed launch transition for task#%d with immersive exit of task#%d",
                taskId, exitingImmersiveTask
            )
        }
        return transitions.startTransition(transitionType, wct, /* handler= */ this)
            .also { transition ->
                pendingMixedTransitions.add(PendingMixedTransition.Launch(
                    transition = transition,
                    launchingTask = taskId,
                    exitingImmersiveTask = exitingImmersiveTask
                ))
            }
    }

    /** Returns null, as it only handles transitions started from Shell. */
    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo,
    ): WindowContainerTransaction? = null

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback,
    ): Boolean {
        val pending = pendingMixedTransitions.find { pending -> pending.transition == transition }
            ?: return false.also {
                logW("Should have pending desktop transition")
            }
        pendingMixedTransitions.remove(pending)
        logV("Animating pending mixed transition: %s", pending)
        return when (pending) {
            is PendingMixedTransition.Close -> animateCloseTransition(
                transition,
                info,
                startTransaction,
                finishTransaction,
                finishCallback
            )
            is PendingMixedTransition.Launch -> animateLaunchTransition(
                pending,
                transition,
                info,
                startTransaction,
                finishTransaction,
                finishCallback
            )
        }
    }

    private fun animateCloseTransition(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback,
    ): Boolean {
        val closeChange = findCloseDesktopTaskChange(info)
        if (closeChange == null) {
            logW("Should have closing desktop task")
            return false
        }
        if (isLastDesktopTask(closeChange)) {
            // Dispatch close desktop task animation to the default transition handlers.
            return dispatchCloseLastDesktopTaskAnimation(
                transition,
                info,
                closeChange,
                startTransaction,
                finishTransaction,
                finishCallback,
            )
        }
        // Animate close desktop task transition with [CloseDesktopTaskTransitionHandler].
        return closeDesktopTaskTransitionHandler.startAnimation(
            transition,
            info,
            startTransaction,
            finishTransaction,
            finishCallback,
        )
    }

    private fun animateLaunchTransition(
        pending: PendingMixedTransition.Launch,
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback,
    ): Boolean {
        // Check if there's also an immersive change during this launch.
        val immersiveExitChange = pending.exitingImmersiveTask?.let { exitingTask ->
            findDesktopTaskChange(info, exitingTask)
        }
        val launchChange = findDesktopTaskChange(info, pending.launchingTask)
            ?: error("Should have pending launching task change")

        var subAnimationCount = -1
        var combinedWct: WindowContainerTransaction? = null
        val finishCb = TransitionFinishCallback { wct ->
            --subAnimationCount
            combinedWct = combinedWct.merge(wct)
            if (subAnimationCount > 0) return@TransitionFinishCallback
            finishCallback.onTransitionFinished(combinedWct)
        }

        logV(
            "Animating pending mixed launch transition task#%d immersiveExitTask#%s",
            launchChange.taskInfo!!.taskId, immersiveExitChange?.taskInfo?.taskId
        )
        if (immersiveExitChange != null) {
            subAnimationCount = 2
            // Animate the immersive exit change separately.
            info.changes.remove(immersiveExitChange)
            desktopImmersiveController.animateResizeChange(
                immersiveExitChange,
                startTransaction,
                finishTransaction,
                finishCb
            )
            // Let the leftover/default handler animate the remaining changes.
            return dispatchToLeftoverHandler(
                transition,
                info,
                startTransaction,
                finishTransaction,
                finishCb
            )
        }
        // There's nothing to animate separately, so let the left over handler animate
        // the entire transition.
        subAnimationCount = 1
        return dispatchToLeftoverHandler(
            transition,
            info,
            startTransaction,
            finishTransaction,
            finishCb
        )
    }

    override fun onTransitionConsumed(
        transition: IBinder,
        aborted: Boolean,
        finishTransaction: SurfaceControl.Transaction?
    ) {
        pendingMixedTransitions.removeAll { pending -> pending.transition == transition }
        super.onTransitionConsumed(transition, aborted, finishTransaction)
    }

    /**
     * Dispatch close desktop task animation to the default transition handlers. Allows delegating
     * it to Launcher to animate in sync with show Home transition.
     */
    private fun dispatchCloseLastDesktopTaskAnimation(
        transition: IBinder,
        info: TransitionInfo,
        change: TransitionInfo.Change,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        // Starting the jank trace if closing the last window in desktop mode.
        interactionJankMonitor.begin(
            change.leash,
            context,
            handler,
            CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE,
        )
        // Dispatch the last desktop task closing animation.
        return dispatchToLeftoverHandler(
            transition = transition,
            info = info,
            startTransaction = startTransaction,
            finishTransaction = finishTransaction,
            finishCallback = finishCallback,
            doOnFinishCallback = {
                // Finish the jank trace when closing the last window in desktop mode.
                interactionJankMonitor.end(CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE)
            }
        )
    }

    private fun dispatchToLeftoverHandler(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: TransitionFinishCallback,
        doOnFinishCallback: (() -> Unit)? = null,
    ): Boolean {
        return transitions.dispatchTransition(
            transition,
            info,
            startTransaction,
            finishTransaction,
            { wct ->
                doOnFinishCallback?.invoke()
                finishCallback.onTransitionFinished(wct)
            },
            /* skip= */ this
        ) != null
    }

    private fun isLastDesktopTask(change: TransitionInfo.Change): Boolean =
        change.taskInfo?.let {
            desktopRepository.getExpandedTaskCount(it.displayId) == 1
        } ?: false

    private fun findCloseDesktopTaskChange(info: TransitionInfo): TransitionInfo.Change? {
        if (info.type != WindowManager.TRANSIT_CLOSE) return null
        return info.changes.firstOrNull { change ->
            change.mode == WindowManager.TRANSIT_CLOSE &&
                !change.hasFlags(TransitionInfo.FLAG_IS_WALLPAPER) &&
                change.taskInfo?.taskId != INVALID_TASK_ID &&
                change.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM
        }
    }

    private fun findDesktopTaskChange(info: TransitionInfo, taskId: Int): TransitionInfo.Change? {
        return info.changes.firstOrNull { change -> change.taskInfo?.taskId == taskId }
    }

    private fun WindowContainerTransaction?.merge(
        wct: WindowContainerTransaction?
    ): WindowContainerTransaction? {
        if (wct == null) return this
        if (this == null) return wct
        return this.merge(wct)
    }

    /** A scheduled transition that will potentially be animated by more than one handler */
    sealed class PendingMixedTransition {
        abstract val transition: IBinder

        /** A task is closing. */
        data class Close(
            override val transition: IBinder,
        ) : PendingMixedTransition()

        /** A task is opening or moving to front. */
        data class Launch(
            override val transition: IBinder,
            val launchingTask: Int,
            val exitingImmersiveTask: Int?,
        ) : PendingMixedTransition()
    }

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private fun logW(msg: String, vararg arguments: Any?) {
        ProtoLog.w(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        private const val TAG = "DesktopMixedTransitionHandler"
    }
}
