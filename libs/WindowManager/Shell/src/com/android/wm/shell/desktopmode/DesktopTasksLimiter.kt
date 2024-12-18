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

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.protolog.ShellProtoLogGroup
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionObserver

/**
 * Limits the number of tasks shown in Desktop Mode.
 *
 * This class should only be used if
 * [android.window.flags.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_TASK_LIMIT]
 * is enabled and [maxTasksLimit] is strictly greater than 0.
 */
class DesktopTasksLimiter (
        transitions: Transitions,
        private val taskRepository: DesktopModeTaskRepository,
        private val shellTaskOrganizer: ShellTaskOrganizer,
        private val maxTasksLimit: Int,
        private val interactionJankMonitor: InteractionJankMonitor,
        private val context: Context,
        @ShellMainThread private val handler: Handler,
) {
    private val minimizeTransitionObserver = MinimizeTransitionObserver()
    @VisibleForTesting
    val leftoverMinimizedTasksRemover = LeftoverMinimizedTasksRemover()

    init {
        require(maxTasksLimit > 0) {
            "DesktopTasksLimiter should not be created with a maxTasksLimit at 0 or less. " +
                    "Current value: $maxTasksLimit."
        }
        transitions.registerObserver(minimizeTransitionObserver)
        taskRepository.addActiveTaskListener(leftoverMinimizedTasksRemover)
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "DesktopTasksLimiter: starting limiter with a maximum of %d tasks", maxTasksLimit)
    }

    private data class TaskDetails(
        val displayId: Int,
        val taskId: Int,
        var transitionInfo: TransitionInfo?
    )

    // TODO(b/333018485): replace this observer when implementing the minimize-animation
    private inner class MinimizeTransitionObserver : TransitionObserver {
        private val pendingTransitionTokensAndTasks = mutableMapOf<IBinder, TaskDetails>()
        private val activeTransitionTokensAndTasks = mutableMapOf<IBinder, TaskDetails>()

        fun addPendingTransitionToken(transition: IBinder, taskDetails: TaskDetails) {
            pendingTransitionTokensAndTasks[transition] = taskDetails
        }

        override fun onTransitionReady(
            transition: IBinder,
            info: TransitionInfo,
            startTransaction: SurfaceControl.Transaction,
            finishTransaction: SurfaceControl.Transaction
        ) {
            val taskToMinimize = pendingTransitionTokensAndTasks.remove(transition) ?: return

            if (!taskRepository.isActiveTask(taskToMinimize.taskId)) return

            if (!isTaskReorderedToBackOrInvisible(info, taskToMinimize)) {
                ProtoLog.v(
                        ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                        "DesktopTasksLimiter: task %d is not reordered to back nor invis",
                        taskToMinimize.taskId)
                return
            }

            taskToMinimize.transitionInfo = info
            activeTransitionTokensAndTasks[transition] = taskToMinimize
            this@DesktopTasksLimiter.markTaskMinimized(
                    taskToMinimize.displayId, taskToMinimize.taskId)
        }

        /**
         * Returns whether the Task [taskDetails] is being reordered to the back in the transition
         * [info], or is already invisible.
         *
         * This check can be used to double-check that a task was indeed minimized before
         * marking it as such.
         */
        private fun isTaskReorderedToBackOrInvisible(
                info: TransitionInfo,
                taskDetails: TaskDetails
        ): Boolean {
            val taskChange = info.changes.find { change ->
                change.taskInfo?.taskId == taskDetails.taskId }
            if (taskChange == null) {
                return !taskRepository.isVisibleTask(taskDetails.taskId)
            }
            return taskChange.mode == TRANSIT_TO_BACK
        }

        override fun onTransitionStarting(transition: IBinder) {
            val mActiveTaskDetails = activeTransitionTokensAndTasks[transition]
            if (mActiveTaskDetails != null && mActiveTaskDetails.transitionInfo != null) {
                // Begin minimize window CUJ instrumentation.
                interactionJankMonitor.begin(
                    mActiveTaskDetails.transitionInfo?.rootLeash, context, handler,
                    CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
                )
            }
        }

        override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
            if (activeTransitionTokensAndTasks.remove(merged) != null) {
                interactionJankMonitor.end(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
            }
            pendingTransitionTokensAndTasks.remove(merged)?.let { taskToTransfer ->
                pendingTransitionTokensAndTasks[playing] = taskToTransfer
            }
        }

        override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
            ProtoLog.v(
                    ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksLimiter: transition %s finished", transition)
            if (activeTransitionTokensAndTasks.remove(transition) != null) {
                if (aborted) {
                    interactionJankMonitor.cancel(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                } else {
                    interactionJankMonitor.end(CUJ_DESKTOP_MODE_MINIMIZE_WINDOW)
                }
            }
            pendingTransitionTokensAndTasks.remove(transition)
        }
    }

    @VisibleForTesting
    inner class LeftoverMinimizedTasksRemover : DesktopModeTaskRepository.ActiveTasksListener {
        override fun onActiveTasksChanged(displayId: Int) {
            val wct = WindowContainerTransaction()
            removeLeftoverMinimizedTasks(displayId, wct)
            shellTaskOrganizer.applyTransaction(wct)
        }

        fun removeLeftoverMinimizedTasks(displayId: Int, wct: WindowContainerTransaction) {
            if (taskRepository.getActiveNonMinimizedOrderedTasks(displayId).isNotEmpty()) {
                return
            }
            val remainingMinimizedTasks = taskRepository.getMinimizedTasks(displayId)
            if (remainingMinimizedTasks.isEmpty()) {
                return
            }
            ProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "DesktopTasksLimiter: removing leftover minimized tasks: %s",
                remainingMinimizedTasks,
            )
            remainingMinimizedTasks.forEach { taskIdToRemove ->
                val taskToRemove = shellTaskOrganizer.getRunningTaskInfo(taskIdToRemove)
                if (taskToRemove != null) {
                    wct.removeTask(taskToRemove.token)
                }
            }
        }
    }

    /**
     * Mark [taskId], which must be on [displayId], as minimized, this should only be done after the
     * corresponding transition has finished so we don't minimize the task if the transition fails.
     */
    private fun markTaskMinimized(displayId: Int, taskId: Int) {
        ProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "DesktopTasksLimiter: marking %d as minimized", taskId)
        taskRepository.minimizeTask(displayId, taskId)
    }

    /**
     * Add a minimize-transition to [wct] if adding [newFrontTaskInfo] brings us over the task
     * limit, returning the task to minimize.
     *
     * The task must be on [displayId].
     */
    fun addAndGetMinimizeTaskChangesIfNeeded(
            displayId: Int,
            wct: WindowContainerTransaction,
            newFrontTaskInfo: RunningTaskInfo,
    ): RunningTaskInfo? {
        ProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "DesktopTasksLimiter: addMinimizeBackTaskChangesIfNeeded, newFrontTask=%d",
                newFrontTaskInfo.taskId)
        val newTaskListOrderedFrontToBack = createOrderedTaskListWithGivenTaskInFront(
                taskRepository.getActiveNonMinimizedOrderedTasks(displayId),
                newFrontTaskInfo.taskId)
        val taskToMinimize = getTaskToMinimizeIfNeeded(newTaskListOrderedFrontToBack)
        if (taskToMinimize != null) {
            wct.reorder(taskToMinimize.token, false /* onTop */)
            return taskToMinimize
        }
        return null
    }

    /**
     * Add a pending minimize transition change, to update the list of minimized apps once the
     * transition goes through.
     */
    fun addPendingMinimizeChange(transition: IBinder, displayId: Int, taskId: Int) {
        minimizeTransitionObserver.addPendingTransitionToken(
                transition, TaskDetails(displayId, taskId, transitionInfo = null))
    }

    /**
     * Returns the Task to minimize given 1. a list of visible tasks ordered from front to back and
     * 2. a new task placed in front of all the others.
     */
    fun getTaskToMinimizeIfNeeded(
            visibleFreeformTaskIdsOrderedFrontToBack: List<Int>,
            newTaskIdInFront: Int
    ): RunningTaskInfo? {
        return getTaskToMinimizeIfNeeded(
                createOrderedTaskListWithGivenTaskInFront(
                        visibleFreeformTaskIdsOrderedFrontToBack, newTaskIdInFront))
    }

    /** Returns the Task to minimize given a list of visible tasks ordered from front to back. */
    fun getTaskToMinimizeIfNeeded(
            visibleFreeformTaskIdsOrderedFrontToBack: List<Int>
    ): RunningTaskInfo? {
        if (visibleFreeformTaskIdsOrderedFrontToBack.size <= maxTasksLimit) {
            ProtoLog.v(
                    ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksLimiter: no need to minimize; tasks below limit")
            // No need to minimize anything
            return null
        }
        val taskIdToMinimize = visibleFreeformTaskIdsOrderedFrontToBack.last()
        val taskToMinimize =
                shellTaskOrganizer.getRunningTaskInfo(taskIdToMinimize)
        if (taskToMinimize == null) {
            ProtoLog.e(
                    ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksLimiter: taskToMinimize(taskId = %d) == null",
                    taskIdToMinimize,
                )
            return null
        }
        return taskToMinimize
    }

    private fun createOrderedTaskListWithGivenTaskInFront(
            existingTaskIdsOrderedFrontToBack: List<Int>,
            newTaskId: Int
    ): List<Int> {
        return listOf(newTaskId) +
                existingTaskIdsOrderedFrontToBack.filter { taskId -> taskId != newTaskId }
    }

    @VisibleForTesting
    fun getTransitionObserver(): TransitionObserver = minimizeTransitionObserver
}