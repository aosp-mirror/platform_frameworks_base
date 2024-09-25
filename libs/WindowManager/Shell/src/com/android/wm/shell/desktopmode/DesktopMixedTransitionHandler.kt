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
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.MixedTransitionHandler
import com.android.wm.shell.transition.Transitions

/** The [Transitions.TransitionHandler] coordinates transition handlers in desktop windowing. */
class DesktopMixedTransitionHandler(
    private val context: Context,
    private val transitions: Transitions,
    private val desktopTaskRepository: DesktopModeTaskRepository,
    private val freeformTaskTransitionHandler: FreeformTaskTransitionHandler,
    private val closeDesktopTaskTransitionHandler: CloseDesktopTaskTransitionHandler,
    private val interactionJankMonitor: InteractionJankMonitor,
    @ShellMainThread private val handler: Handler,
) : MixedTransitionHandler, FreeformTaskTransitionStarter {

    /** Delegates starting transition to [FreeformTaskTransitionHandler]. */
    override fun startWindowingModeTransition(
        targetWindowingMode: Int,
        wct: WindowContainerTransaction?,
    ) = freeformTaskTransitionHandler.startWindowingModeTransition(targetWindowingMode, wct)

    /** Delegates starting minimized mode transition to [FreeformTaskTransitionHandler]. */
    override fun startMinimizedModeTransition(wct: WindowContainerTransaction?): IBinder =
        freeformTaskTransitionHandler.startMinimizedModeTransition(wct)

    /** Starts close transition and handles or delegates desktop task close animation. */
    override fun startRemoveTransition(wct: WindowContainerTransaction?) {
        requireNotNull(wct)
        transitions.startTransition(WindowManager.TRANSIT_CLOSE, wct, /* handler= */ this)
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
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        val closeChange = findCloseDesktopTaskChange(info)
        if (closeChange == null) {
            ProtoLog.w(WM_SHELL_DESKTOP_MODE, "%s: Should have closing desktop task", TAG)
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
        return transitions.dispatchTransition(
            transition,
            info,
            startTransaction,
            finishTransaction,
            { wct ->
                // Finish the jank trace when closing the last window in desktop mode.
                interactionJankMonitor.end(CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE)
                finishCallback.onTransitionFinished(wct)
            },
            /* skip= */ this
        ) != null
    }

    private fun isLastDesktopTask(change: TransitionInfo.Change): Boolean =
        change.taskInfo?.let {
            desktopTaskRepository.getActiveNonMinimizedTaskCount(it.displayId) == 1
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

    companion object {
        private const val TAG = "DesktopMixedTransitionHandler"
    }
}
