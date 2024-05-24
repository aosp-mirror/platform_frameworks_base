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
import java.io.PrintWriter
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
        var stashed: Boolean = false
    )

    // Tasks currently in freeform mode, ordered from top to bottom (top is at index 0).
    private val freeformTasksInZOrder = mutableListOf<Int>()
    private val activeTasksListeners = ArraySet<ActiveTasksListener>()
    // Track visible tasks separately because a task may be part of the desktop but not visible.
    private val visibleTasksListeners = ArrayMap<VisibleTasksListener, Executor>()
    // Track corner/caption regions of desktop tasks, used to determine gesture exclusion
    private val desktopExclusionRegions = SparseArray<Region>()
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
            val visibleTasksCount = getVisibleTaskCount(displayId)
            val stashed = isStashed(displayId)
            executor.execute {
                visibleTasksListener.onTasksVisibilityChanged(displayId, visibleTasksCount)
                visibleTasksListener.onStashedChanged(displayId, stashed)
            }
        }
    }

    /**
     * Add a Consumer which will inform other classes of changes to exclusion regions for all
     * Desktop tasks.
     */
    fun setExclusionRegionListener(regionListener: Consumer<Region>, executor: Executor) {
        desktopGestureExclusionListener = regionListener
        desktopGestureExclusionExecutor = executor
        executor.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Create a new merged region representative of all exclusion regions in all desktop tasks.
     */
    private fun calculateDesktopExclusionRegion(): Region {
        val desktopExclusionRegion = Region()
        desktopExclusionRegions.valueIterator().forEach { taskExclusionRegion ->
            desktopExclusionRegion.op(taskExclusionRegion, Region.Op.UNION)
        }
        return desktopExclusionRegion
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
                    notifyVisibleTaskListeners(otherDisplayId,
                        displayData[otherDisplayId].visibleTasks.size)
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

        // Check if count changed
        if (prevCount != newCount) {
            KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTaskRepo: visibleTaskCount has changed from %d to %d",
                prevCount,
                newCount
            )
            notifyVisibleTaskListeners(displayId, newCount)
        }
    }

    private fun notifyVisibleTaskListeners(displayId: Int, visibleTasksCount: Int) {
        visibleTasksListeners.forEach { (listener, executor) ->
            executor.execute { listener.onTasksVisibilityChanged(displayId, visibleTasksCount) }
        }
    }

    /**
     * Get number of tasks that are marked as visible on given [displayId]
     */
    fun getVisibleTaskCount(displayId: Int): Int {
        KtProtoLog.d(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTaskRepo: visibleTaskCount= %d",
            displayData[displayId]?.visibleTasks?.size ?: 0
        )
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
        KtProtoLog.d(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTaskRepo: remaining freeform tasks: " + freeformTasksInZOrder.toDumpString()
        )
    }

    /**
     * Updates the active desktop gesture exclusion regions; if desktopExclusionRegions has been
     * accepted by desktopGestureExclusionListener, it will be updated in the
     * appropriate classes.
     */
    fun updateTaskExclusionRegions(taskId: Int, taskExclusionRegions: Region) {
        desktopExclusionRegions.put(taskId, taskExclusionRegions)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Removes the desktop gesture exclusion region for the specified task; if exclusionRegion
     * has been accepted by desktopGestureExclusionListener, it will be updated in the
     * appropriate classes.
     */
    fun removeExclusionRegion(taskId: Int) {
        desktopExclusionRegions.delete(taskId)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Update stashed status on display with id [displayId]
     */
    fun setStashed(displayId: Int, stashed: Boolean) {
        val data = displayData.getOrCreate(displayId)
        val oldValue = data.stashed
        data.stashed = stashed
        if (oldValue != stashed) {
            KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTaskRepo: mark stashed=%b displayId=%d",
                    stashed,
                    displayId
            )
            visibleTasksListeners.forEach { (listener, executor) ->
                executor.execute { listener.onStashedChanged(displayId, stashed) }
            }
        }
    }

    /**
     * Check if display with id [displayId] has desktop tasks stashed
     */
    fun isStashed(displayId: Int): Boolean {
        return displayData[displayId]?.stashed ?: false
    }

    internal fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopModeTaskRepository")
        dumpDisplayData(pw, innerPrefix)
        pw.println("${innerPrefix}freeformTasksInZOrder=${freeformTasksInZOrder.toDumpString()}")
        pw.println("${innerPrefix}activeTasksListeners=${activeTasksListeners.size}")
        pw.println("${innerPrefix}visibleTasksListeners=${visibleTasksListeners.size}")
    }

    private fun dumpDisplayData(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        displayData.forEach { displayId, data ->
            pw.println("${prefix}Display $displayId:")
            pw.println("${innerPrefix}activeTasks=${data.activeTasks.toDumpString()}")
            pw.println("${innerPrefix}visibleTasks=${data.visibleTasks.toDumpString()}")
            pw.println("${innerPrefix}stashed=${data.stashed}")
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
         * Called when the desktop changes the number of visible freeform tasks.
         */
        fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {}

        /**
         * Called when the desktop stashed status changes.
         */
        fun onStashedChanged(displayId: Int, stashed: Boolean) {}
    }
}

private fun <T> Iterable<T>.toDumpString(): String {
    return joinToString(separator = ", ", prefix = "[", postfix = "]")
}
