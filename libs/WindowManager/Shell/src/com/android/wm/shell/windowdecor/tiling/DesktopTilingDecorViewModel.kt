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

package com.android.wm.shell.windowdecor.tiling

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.Rect
import android.util.SparseArray
import android.window.DisplayAreaInfo
import android.window.WindowContainerTransaction
import androidx.core.util.valueIterator
import com.android.internal.annotations.VisibleForTesting
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayChangeController
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopModeEventLogger
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.ReturnToDragStartAnimator
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler
import com.android.wm.shell.shared.annotations.ShellBackgroundThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.FocusTransitionObserver
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.common.WindowDecorTaskResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher

/** Manages tiling for each displayId/userId independently. */
class DesktopTilingDecorViewModel(
    private val context: Context,
    @ShellMainThread private val mainDispatcher: MainCoroutineDispatcher,
    @ShellBackgroundThread private val bgScope: CoroutineScope,
    private val displayController: DisplayController,
    private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    private val syncQueue: SyncTransactionQueue,
    private val transitions: Transitions,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler,
    private val returnToDragStartAnimator: ReturnToDragStartAnimator,
    private val desktopUserRepositories: DesktopUserRepositories,
    private val desktopModeEventLogger: DesktopModeEventLogger,
    private val taskResourceLoader: WindowDecorTaskResourceLoader,
    private val focusTransitionObserver: FocusTransitionObserver,
    private val mainExecutor: ShellExecutor,
) : DisplayChangeController.OnDisplayChangingListener {
    @VisibleForTesting
    var tilingTransitionHandlerByDisplayId = SparseArray<DesktopTilingWindowDecoration>()

    init {
        // TODO(b/374309287): Move this interface implementation to
        // [DesktopModeWindowDecorViewModel] when the migration is done.
        displayController.addDisplayChangingController(this)
    }

    fun snapToHalfScreen(
        taskInfo: ActivityManager.RunningTaskInfo,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
        position: DesktopTasksController.SnapPosition,
        destinationBounds: Rect,
    ): Boolean {
        val displayId = taskInfo.displayId
        val handler =
            tilingTransitionHandlerByDisplayId.get(displayId)
                ?: run {
                    val newHandler =
                        DesktopTilingWindowDecoration(
                            context,
                            mainDispatcher,
                            bgScope,
                            syncQueue,
                            displayController,
                            taskResourceLoader,
                            displayId,
                            rootTdaOrganizer,
                            transitions,
                            shellTaskOrganizer,
                            toggleResizeDesktopTaskTransitionHandler,
                            returnToDragStartAnimator,
                            desktopUserRepositories,
                            desktopModeEventLogger,
                            focusTransitionObserver,
                            mainExecutor,
                        )
                    tilingTransitionHandlerByDisplayId.put(displayId, newHandler)
                    newHandler
                }
        transitions.registerObserver(handler)
        return handler.onAppTiled(
            taskInfo,
            desktopModeWindowDecoration,
            position,
            destinationBounds,
        )
    }

    fun removeTaskIfTiled(displayId: Int, taskId: Int) {
        tilingTransitionHandlerByDisplayId.get(displayId)?.removeTaskIfTiled(taskId)
    }

    fun moveTaskToFrontIfTiled(taskInfo: RunningTaskInfo): Boolean {
        // Always pass focus=true because taskInfo.isFocused is not updated yet.
        return tilingTransitionHandlerByDisplayId
            .get(taskInfo.displayId)
            ?.moveTiledPairToFront(taskInfo.taskId, isFocusedOnDisplay = true) ?: false
    }

    fun onOverviewAnimationStateChange(isRunning: Boolean) {
        for (tilingHandler in tilingTransitionHandlerByDisplayId.valueIterator()) {
            tilingHandler.onOverviewAnimationStateChange(isRunning)
        }
    }

    fun onUserChange() {
        for (tilingHandler in tilingTransitionHandlerByDisplayId.valueIterator()) {
            tilingHandler.resetTilingSession()
        }
    }

    override fun onDisplayChange(
        displayId: Int,
        fromRotation: Int,
        toRotation: Int,
        newDisplayAreaInfo: DisplayAreaInfo?,
        t: WindowContainerTransaction?,
    ) {
        // Exit if the rotation hasn't changed or is changed by 180 degrees. [fromRotation] and
        // [toRotation] can be one of the [@Surface.Rotation] values.
        if ((fromRotation % 2 == toRotation % 2)) return
        tilingTransitionHandlerByDisplayId.get(displayId)?.resetTilingSession()
    }
}
