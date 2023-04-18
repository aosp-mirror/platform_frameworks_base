/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.util.ArrayMap
import android.util.ArraySet
import java.util.concurrent.Executor

/**
 * Keeps track of task data related to desktop mode.
 */
class DesktopModeTaskRepository {

    /**
     * Set of task ids that are marked as active in desktop mode.
     * Active tasks in desktop mode are freeform tasks that are visible or have been visible after
     * desktop mode was activated.
     * Task gets removed from this list when it vanishes. Or when desktop mode is turned off.
     */
    private val activeTasks = ArraySet<Int>()
    private val visibleTasks = ArraySet<Int>()
    // Tasks currently in freeform mode, ordered from top to bottom (top is at index 0).
    private val freeformTasksInZOrder = mutableListOf<Int>()
    private val activeTasksListeners = ArraySet<ActiveTasksListener>()
    // Track visible tasks separately because a task may be part of the desktop but not visible.
    private val visibleTasksListeners = ArrayMap<VisibleTasksListener, Executor>()

    /**
     * Add a [ActiveTasksListener] to be notified of updates to active tasks in the repository.
     */
    fun addActiveTaskListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.add(activeTasksListener)
    }

    /**
     * Add a [VisibleTasksListener] to be notified when freeform tasks are visible or not.
     */
    fun addVisibleTasksListener(visibleTasksListener: VisibleTasksListener, executor: Executor) {
        visibleTasksListeners.put(visibleTasksListener, executor)
        executor.execute(
                Runnable { visibleTasksListener.onVisibilityChanged(visibleTasks.size > 0) })
    }

    /**
     * Remove a previously registered [ActiveTasksListener]
     */
    fun removeActiveTasksListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.remove(activeTasksListener)
    }

    /**
     * Remove a previously registered [VisibleTasksListener]
     */
    fun removeVisibleTasksListener(visibleTasksListener: VisibleTasksListener) {
        visibleTasksListeners.remove(visibleTasksListener)
    }

    /**
     * Mark a task with given [taskId] as active.
     *
     * @return `true` if the task was not active
     */
    fun addActiveTask(taskId: Int): Boolean {
        val added = activeTasks.add(taskId)
        if (added) {
            activeTasksListeners.onEach { it.onActiveTasksChanged() }
        }
        return added
    }

    /**
     * Remove task with given [taskId] from active tasks.
     *
     * @return `true` if the task was active
     */
    fun removeActiveTask(taskId: Int): Boolean {
        val removed = activeTasks.remove(taskId)
        if (removed) {
            activeTasksListeners.onEach { it.onActiveTasksChanged() }
        }
        return removed
    }

    /**
     * Check if a task with the given [taskId] was marked as an active task
     */
    fun isActiveTask(taskId: Int): Boolean {
        return activeTasks.contains(taskId)
    }

    /**
     * Whether a task is visible.
     */
    fun isVisibleTask(taskId: Int): Boolean {
        return visibleTasks.contains(taskId)
    }

    /**
     * Get a set of the active tasks
     */
    fun getActiveTasks(): ArraySet<Int> {
        return ArraySet(activeTasks)
    }

    /**
     * Get a list of freeform tasks, ordered from top-bottom (top at index 0).
     */
    fun getFreeformTasksInZOrder(): List<Int> {
        return freeformTasksInZOrder
    }

    /**
     * Updates whether a freeform task with this id is visible or not and notifies listeners.
     */
    fun updateVisibleFreeformTasks(taskId: Int, visible: Boolean) {
        val prevCount: Int = visibleTasks.size
        if (visible) {
            visibleTasks.add(taskId)
        } else {
            visibleTasks.remove(taskId)
        }
        if (prevCount == 0 && visibleTasks.size == 1 ||
                prevCount > 0 && visibleTasks.size == 0) {
            for ((listener, executor) in visibleTasksListeners) {
                executor.execute(
                        Runnable { listener.onVisibilityChanged(visibleTasks.size > 0) })
            }
        }
    }

    /**
     * Get number of tasks that are marked as visible
     */
    fun getVisibleTaskCount(): Int {
        return visibleTasks.size
    }

    /**
     * Add (or move if it already exists) the task to the top of the ordered list.
     */
    fun addOrMoveFreeformTaskToTop(taskId: Int) {
        if (freeformTasksInZOrder.contains(taskId)) {
            freeformTasksInZOrder.remove(taskId)
        }
        freeformTasksInZOrder.add(0, taskId)
    }

    /**
     * Remove the task from the ordered list.
     */
    fun removeFreeformTask(taskId: Int) {
        freeformTasksInZOrder.remove(taskId)
    }

    /**
     * Defines interface for classes that can listen to changes for active tasks in desktop mode.
     */
    interface ActiveTasksListener {
        /**
         * Called when the active tasks change in desktop mode.
         */
        @JvmDefault
        fun onActiveTasksChanged() {}
    }

    /**
     * Defines interface for classes that can listen to changes for visible tasks in desktop mode.
     */
    interface VisibleTasksListener {
        /**
         * Called when the desktop starts or stops showing freeform tasks.
         */
        @JvmDefault
        fun onVisibilityChanged(hasVisibleFreeformTasks: Boolean) {}
    }
}
