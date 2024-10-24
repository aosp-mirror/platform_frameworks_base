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
import android.window.DesktopModeFlags
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_MINIMIZE_WINDOW
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionObserver

/**
 * Limits the number of tasks shown in Desktop Mode.
 *
 * This class should only be used if
 * [android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_TASK_LIMIT]
 * is enabled and [maxTasksLimit] is strictly greater than 0.
 */
class DesktopTasksLimiter (
        transitions: Transitions,
        private val taskRepository: DesktopRepository,
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
            "DesktopTasksLimiter: maxTasksLimit should be greater than 0. Current value: $maxTasksLimit."
        }
        transitions.registerObserver(minimizeTransitionObserver)
        taskRepository.addActiveTaskListener(leftoverMinimizedTasksRemover)
        logV("Starting limiter with a maximum of %d tasks", maxTasksLimit)
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
            if (!isTaskReadyForMinimize(info, taskToMinimize)) {
                logV("task %d is not reordered to back nor invis", taskToMinimize.taskId)
                return
            }
            taskToMinimize.transitionInfo = info
            activeTransitionTokensAndTasks[transition] = taskToMinimize
            this@DesktopTasksLimiter.minimizeTask(
                    taskToMinimize.displayId, taskToMinimize.taskId)
        }

        /**
         * Returns whether the Task [taskDetails] is being reordered to the back in the transition
         * [info], or is already invisible.
         *
         * This check confirms a task should be minimized before minimizing it.
         */
        private fun isTaskReadyForMinimize(
            info: TransitionInfo,
            taskDetails: TaskDetails
        ): Boolean {
            val taskChange = info.changes.find { change ->
                change.taskInfo?.taskId == taskDetails.taskId }
            if (taskChange == null) return !taskRepository.isVisibleTask(taskDetails.taskId)
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
            logV("transition %s finished", transition)
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
    inner class LeftoverMinimizedTasksRemover : DesktopRepository.ActiveTasksListener {
        override fun onActiveTasksChanged(displayId: Int) {
            // If back navigation is enabled, we shouldn't remove the leftover tasks
            if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) return
            val wct = WindowContainerTransaction()
            removeLeftoverMinimizedTasks(displayId, wct)
            shellTaskOrganizer.applyTransaction(wct)
        }

        fun removeLeftoverMinimizedTasks(displayId: Int, wct: WindowContainerTransaction) {
            if (taskRepository.getExpandedTasksOrdered(displayId).isNotEmpty()) return
            val remainingMinimizedTasks = taskRepository.getMinimizedTasks(displayId)
            if (remainingMinimizedTasks.isEmpty()) return

            logV("Removing leftover minimized tasks: %s", remainingMinimizedTasks)
            remainingMinimizedTasks.forEach { taskIdToRemove ->
                val taskToRemove = shellTaskOrganizer.getRunningTaskInfo(taskIdToRemove)
                if (taskToRemove != null) {
                    wct.removeTask(taskToRemove.token)
                }
            }
        }
    }

    /**
     * Mark task with [taskId] on [displayId] as minimized.
     *
     * This should be after the corresponding transition has finished so we don't
     * minimize the task if the transition fails.
     */
    private fun minimizeTask(displayId: Int, taskId: Int) {
        logV("Minimize taskId=%d, displayId=%d", taskId, displayId)
        taskRepository.minimizeTask(displayId, taskId)
    }

    /**
     * Adds a minimize-transition to [wct] if adding [newFrontTaskInfo] crosses task
     * limit, returning the task to minimize.
     */
    fun addAndGetMinimizeTaskChanges(
            displayId: Int,
            wct: WindowContainerTransaction,
            newFrontTaskId: Int,
    ): RunningTaskInfo? {
        logV("addAndGetMinimizeTaskChanges, newFrontTask=%d", newFrontTaskId)
        // This list is ordered from front to back.
        val newTaskOrderedList = createOrderedTaskListWithNewTask(
            taskRepository.getExpandedTasksOrdered(displayId), newFrontTaskId)
        val taskToMinimize = getTaskToMinimize(newTaskOrderedList)
        if (taskToMinimize != null) {
            wct.reorder(taskToMinimize.token, false /* onTop */)
            return taskToMinimize
        }
        return null
    }

    /**
     * Add a pending minimize transition change to update the list of minimized apps once the
     * transition goes through.
     */
    fun addPendingMinimizeChange(transition: IBinder, displayId: Int, taskId: Int) {
        minimizeTransitionObserver.addPendingTransitionToken(
                transition, TaskDetails(displayId, taskId, transitionInfo = null))
    }

    /**
     * Returns the minimized task from the list of visible tasks ordered from front to back with
     * the new task placed in front of other tasks.
     */
    fun getTaskToMinimize(
            visibleOrderedTasks: List<Int>,
            newTaskIdInFront: Int
    ): RunningTaskInfo? =
        getTaskToMinimize(createOrderedTaskListWithNewTask(visibleOrderedTasks, newTaskIdInFront))

    /** Returns the Task to minimize given a list of visible tasks ordered from front to back. */
    fun getTaskToMinimize(visibleOrderedTasks: List<Int>): RunningTaskInfo? {
        if (visibleOrderedTasks.size <= maxTasksLimit) {
            logV("No need to minimize; tasks below limit")
            return null
        }
        val taskIdToMinimize = visibleOrderedTasks.last()
        val taskToMinimize =
                shellTaskOrganizer.getRunningTaskInfo(taskIdToMinimize)
        if (taskToMinimize == null) {
            logE("taskToMinimize(taskId = %d) == null", taskIdToMinimize)
            return null
        }
        return taskToMinimize
    }

    private fun createOrderedTaskListWithNewTask(
        orderedTaskIds: List<Int>, newTaskId: Int): List<Int> =
            listOf(newTaskId) + orderedTaskIds.filter { taskId -> taskId != newTaskId }

    @VisibleForTesting
    fun getTransitionObserver(): TransitionObserver = minimizeTransitionObserver

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private fun logE(msg: String, vararg arguments: Any?) {
        ProtoLog.e(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private companion object {
        const val TAG = "DesktopTasksLimiter"
    }
}
