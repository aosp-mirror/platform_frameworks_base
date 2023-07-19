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

import android.graphics.Region
import android.util.ArrayMap
import android.util.ArraySet
import android.util.SparseArray
import androidx.core.util.forEach
import androidx.core.util.keyIterator
import androidx.core.util.valueIterator
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.util.KtProtoLog
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Keeps track of task data related to desktop mode.
 */
class DesktopModeTaskRepository {

    /** Task data that is tracked per display */
    private data class DisplayData(
        /**
         * Set of task ids that are marked as active in desktop mode. Active tasks in desktop mode
         * are freeform tasks that are visible or have been visible after desktop mode was
         * activated. Task gets removed from this list when it vanishes. Or when desktop mode is
         * turned off.
         */
        val activeTasks: ArraySet<Int> = ArraySet(),
        val visibleTasks: ArraySet<Int> = ArraySet(),
    )

    // Tasks currently in freeform mode, ordered from top to bottom (top is at index 0).
    private val freeformTasksInZOrder = mutableListOf<Int>()
    private val activeTasksListeners = ArraySet<ActiveTasksListener>()
    // Track visible tasks separately because a task may be part of the desktop but not visible.
    private val visibleTasksListeners = ArrayMap<VisibleTasksListener, Executor>()
    // Track corners of desktop tasks, used to determine gesture exclusion
    private val desktopCorners = SparseArray<Region>()
    private var desktopGestureExclusionListener: Consumer<Region>? = null
    private var desktopGestureExclusionExecutor: Executor? = null

    private val displayData =
        object : SparseArray<DisplayData>() {
            /**
             * Get the [DisplayData] associated with this [displayId]
             *
             * Creates a new instance if one does not exist
             */
            fun getOrCreate(displayId: Int): DisplayData {
                if (!contains(displayId)) {
                    put(displayId, DisplayData())
                }
                return get(displayId)
            }
        }

    /** Add a [ActiveTasksListener] to be notified of updates to active tasks in the repository. */
    fun addActiveTaskListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.add(activeTasksListener)
    }

    /**
     * Add a [VisibleTasksListener] to be notified when freeform tasks are visible or not.
     */
    fun addVisibleTasksListener(
        visibleTasksListener: VisibleTasksListener,
        executor: Executor
    ) {
        visibleTasksListeners[visibleTasksListener] = executor
        displayData.keyIterator().forEach { displayId ->
            val visibleTasks = getVisibleTaskCount(displayId)
            executor.execute {
                visibleTasksListener.onVisibilityChanged(displayId, visibleTasks > 0)
            }
        }
    }

    /**
     * Add a Consumer which will inform other classes of changes to corners for all Desktop tasks.
     */
    fun setTaskCornerListener(cornersListener: Consumer<Region>, executor: Executor) {
        desktopGestureExclusionListener = cornersListener
        desktopGestureExclusionExecutor = executor
        executor.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Create a new merged region representative of all corners in all desktop tasks.
     */
    private fun calculateDesktopExclusionRegion(): Region {
        val desktopCornersRegion = Region()
        desktopCorners.valueIterator().forEach { taskCorners ->
            desktopCornersRegion.op(taskCorners, Region.Op.UNION)
        }
        return desktopCornersRegion
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
     * Mark a task with given [taskId] as active on given [displayId]
     *
     * @return `true` if the task was not active on given [displayId]
     */
    fun addActiveTask(displayId: Int, taskId: Int): Boolean {
        // Check if task is active on another display, if so, remove it
        displayData.forEach { id, data ->
            if (id != displayId && data.activeTasks.remove(taskId)) {
                activeTasksListeners.onEach { it.onActiveTasksChanged(id) }
            }
        }

        val added = displayData.getOrCreate(displayId).activeTasks.add(taskId)
        if (added) {
            KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTaskRepo: add active task=%d displayId=%d",
                taskId,
                displayId
            )
            activeTasksListeners.onEach { it.onActiveTasksChanged(displayId) }
        }
        return added
    }

    /**
     * Remove task with given [taskId] from active tasks.
     *
     * @return `true` if the task was active
     */
    fun removeActiveTask(taskId: Int): Boolean {
        var result = false
        displayData.forEach { displayId, data ->
            if (data.activeTasks.remove(taskId)) {
                activeTasksListeners.onEach { it.onActiveTasksChanged(displayId) }
                result = true
            }
        }
        if (result) {
            KtProtoLog.d(WM_SHELL_DESKTOP_MODE, "DesktopTaskRepo: remove active task=%d", taskId)
        }
        return result
    }

    /**
     * Check if a task with the given [taskId] was marked as an active task
     */
    fun isActiveTask(taskId: Int): Boolean {
        return displayData.valueIterator().asSequence().any { data ->
            data.activeTasks.contains(taskId)
        }
    }

    /**
     * Whether a task is visible.
     */
    fun isVisibleTask(taskId: Int): Boolean {
        return displayData.valueIterator().asSequence().any { data ->
            data.visibleTasks.contains(taskId)
        }
    }

    /**
     * Get a set of the active tasks for given [displayId]
     */
    fun getActiveTasks(displayId: Int): ArraySet<Int> {
        return ArraySet(displayData[displayId]?.activeTasks)
    }

    /**
     * Get a list of freeform tasks, ordered from top-bottom (top at index 0).
     */
     // TODO(b/278084491): pass in display id
    fun getFreeformTasksInZOrder(): List<Int> {
        return freeformTasksInZOrder
    }

    /**
     * Updates whether a freeform task with this id is visible or not and notifies listeners.
     *
     * If the task was visible on a different display with a different displayId, it is removed from
     * the set of visible tasks on that display. Listeners will be notified.
     */
    fun updateVisibleFreeformTasks(displayId: Int, taskId: Int, visible: Boolean) {
        if (visible) {
            // Task is visible. Check if we need to remove it from any other display.
            val otherDisplays = displayData.keyIterator().asSequence().filter { it != displayId }
            for (otherDisplayId in otherDisplays) {
                if (displayData[otherDisplayId].visibleTasks.remove(taskId)) {
                    // Task removed from other display, check if we should notify listeners
                    if (displayData[otherDisplayId].visibleTasks.isEmpty()) {
                        notifyVisibleTaskListeners(otherDisplayId, hasVisibleFreeformTasks = false)
                    }
                }
            }
        }

        val prevCount = getVisibleTaskCount(displayId)
        if (visible) {
            displayData.getOrCreate(displayId).visibleTasks.add(taskId)
        } else {
            displayData[displayId]?.visibleTasks?.remove(taskId)
        }
        val newCount = getVisibleTaskCount(displayId)

        if (prevCount != newCount) {
            KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTaskRepo: update task visibility taskId=%d visible=%b displayId=%d",
                taskId,
                visible,
                displayId
            )
        }

        // Check if count changed and if there was no tasks or this is the first task
        if (prevCount != newCount && (prevCount == 0 || newCount == 0)) {
            notifyVisibleTaskListeners(displayId, newCount > 0)
        }
    }

    private fun notifyVisibleTaskListeners(displayId: Int, hasVisibleFreeformTasks: Boolean) {
        visibleTasksListeners.forEach { (listener, executor) ->
            executor.execute { listener.onVisibilityChanged(displayId, hasVisibleFreeformTasks) }
        }
    }

    /**
     * Get number of tasks that are marked as visible on given [displayId]
     */
    fun getVisibleTaskCount(displayId: Int): Int {
        return displayData[displayId]?.visibleTasks?.size ?: 0
    }

    /**
     * Add (or move if it already exists) the task to the top of the ordered list.
     */
    fun addOrMoveFreeformTaskToTop(taskId: Int) {
        KtProtoLog.d(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTaskRepo: add or move task to top taskId=%d",
            taskId
        )
        if (freeformTasksInZOrder.contains(taskId)) {
            freeformTasksInZOrder.remove(taskId)
        }
        freeformTasksInZOrder.add(0, taskId)
    }

    /**
     * Remove the task from the ordered list.
     */
    fun removeFreeformTask(taskId: Int) {
        KtProtoLog.d(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTaskRepo: remove freeform task from ordered list taskId=%d",
            taskId
        )
        freeformTasksInZOrder.remove(taskId)
    }

    /**
     * Updates the active desktop corners; if desktopCorners has been accepted by
     * desktopCornersListener, it will be updated in the appropriate classes.
     */
    fun updateTaskCorners(taskId: Int, taskCorners: Region) {
        desktopCorners.put(taskId, taskCorners)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Removes the active desktop corners for the specified task; if desktopCorners has been
     * accepted by desktopCornersListener, it will be updated in the appropriate classes.
     */
    fun removeTaskCorners(taskId: Int) {
        desktopCorners.delete(taskId)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Defines interface for classes that can listen to changes for active tasks in desktop mode.
     */
    interface ActiveTasksListener {
        /**
         * Called when the active tasks change in desktop mode.
         */
        fun onActiveTasksChanged(displayId: Int) {}
    }

    /**
     * Defines interface for classes that can listen to changes for visible tasks in desktop mode.
     */
    interface VisibleTasksListener {
        /**
         * Called when the desktop starts or stops showing freeform tasks.
         */
        fun onVisibilityChanged(displayId: Int, hasVisibleFreeformTasks: Boolean) {}
    }
}
