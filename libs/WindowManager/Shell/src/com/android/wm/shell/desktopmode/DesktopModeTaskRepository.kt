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

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.util.ArrayMap
import android.util.ArraySet
import android.util.SparseArray
import android.view.Display.INVALID_DISPLAY
import android.window.WindowContainerToken
import androidx.core.util.forEach
import androidx.core.util.keyIterator
import androidx.core.util.valueIterator
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository
import com.android.wm.shell.desktopmode.persistence.DesktopTask
import com.android.wm.shell.desktopmode.persistence.DesktopTaskState
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Tracks task data for Desktop Mode. */
class DesktopModeTaskRepository (
    private val context: Context,
    shellInit: ShellInit,
    private val persistentRepository: DesktopPersistentRepository,
    @ShellMainThread private val mainCoroutineScope: CoroutineScope,
){

    /**
     * Task data tracked per desktop.
     *
     * @property activeTasks task ids of active tasks currently or previously visible in Desktop
     * mode session. Tasks become inactive when task closes or when desktop mode session ends.
     * @property visibleTasks task ids for active freeform tasks that are currently visible. There
     * might be other active tasks in desktop mode that are not visible.
     * @property minimizedTasks task ids for active freeform tasks that are currently minimized.
     * @property closingTasks task ids for tasks that are going to close, but are currently visible.
     * @property freeformTasksInZOrder list of current freeform task ids ordered from top to bottom
     * (top is at index 0).
     */
    private data class DesktopTaskData(
        val activeTasks: ArraySet<Int> = ArraySet(),
        val visibleTasks: ArraySet<Int> = ArraySet(),
        val minimizedTasks: ArraySet<Int> = ArraySet(),
        // TODO(b/332682201): Remove when the repository state is updated via TransitionObserver
        val closingTasks: ArraySet<Int> = ArraySet(),
        val freeformTasksInZOrder: ArrayList<Int> = ArrayList(),
    ) {
        fun deepCopy(): DesktopTaskData = DesktopTaskData(
            activeTasks = ArraySet(activeTasks),
            visibleTasks = ArraySet(visibleTasks),
            minimizedTasks = ArraySet(minimizedTasks),
            closingTasks = ArraySet(closingTasks),
            freeformTasksInZOrder = ArrayList(freeformTasksInZOrder)
        )
    }

    /* Current wallpaper activity token to remove wallpaper activity when last task is removed. */
    var wallpaperActivityToken: WindowContainerToken? = null

    private val activeTasksListeners = ArraySet<ActiveTasksListener>()
    private val visibleTasksListeners = ArrayMap<VisibleTasksListener, Executor>()

    /* Tracks corner/caption regions of desktop tasks, used to determine gesture exclusion. */
    private val desktopExclusionRegions = SparseArray<Region>()

    /* Tracks last bounds of task before toggled to stable bounds. */
    private val boundsBeforeMaximizeByTaskId = SparseArray<Rect>()

    private var desktopGestureExclusionListener: Consumer<Region>? = null
    private var desktopGestureExclusionExecutor: Executor? = null

    private val desktopTaskDataByDisplayId = object : SparseArray<DesktopTaskData>() {
        /** Gets [DesktopTaskData] for existing [displayId] or creates a new one. */
        fun getOrCreate(displayId: Int): DesktopTaskData =
            this[displayId] ?: DesktopTaskData().also { this[displayId] = it }
    }

    init {
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            shellInit.addInitCallback(::initRepoFromPersistentStorage, this)
        }
    }

    private fun initRepoFromPersistentStorage() {
        if (!Flags.enableDesktopWindowingPersistence()) return
        //  TODO: b/365962554 - Handle the case that user moves to desktop before it's initialized
        mainCoroutineScope.launch {
            val desktop = persistentRepository.readDesktop()
            val maxTasks =
                DesktopModeStatus.getMaxTaskLimit(context).takeIf { it > 0 }
                    ?: desktop.zOrderedTasksCount

            desktop.zOrderedTasksList
                // Reverse it so we initialize the repo from bottom to top.
                .reversed()
                .map { taskId ->
                    desktop.tasksByTaskIdMap.getOrDefault(
                        taskId,
                        DesktopTask.getDefaultInstance()
                    )
                }
                .filter { task -> task.desktopTaskState == DesktopTaskState.VISIBLE }
                .take(maxTasks)
                .forEach { task ->
                    addOrMoveFreeformTaskToTop(desktop.displayId, task.taskId)
                    addActiveTask(desktop.displayId, task.taskId)
                    updateTaskVisibility(desktop.displayId, task.taskId, visible = false)
                }
        }
    }

    /** Adds [activeTasksListener] to be notified of updates to active tasks. */
    fun addActiveTaskListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.add(activeTasksListener)
    }

    /** Adds [visibleTasksListener] to be notified of updates to visible tasks. */
    fun addVisibleTasksListener(visibleTasksListener: VisibleTasksListener, executor: Executor) {
        visibleTasksListeners[visibleTasksListener] = executor
        desktopTaskDataByDisplayId.keyIterator().forEach {
            val visibleTaskCount = getVisibleTaskCount(it)
            executor.execute {
                visibleTasksListener.onTasksVisibilityChanged(it, visibleTaskCount)
            }
        }
    }

    /** Updates tasks changes on all the active task listeners for given display id. */
    private fun updateActiveTasksListeners(displayId: Int) {
        activeTasksListeners.onEach { it.onActiveTasksChanged(displayId) }
    }

    /** Returns a list of all [DesktopTaskData] in the repository. */
    private fun desktopTaskDataSequence(): Sequence<DesktopTaskData> =
        desktopTaskDataByDisplayId.valueIterator().asSequence()

    /** Adds [regionListener] to inform about changes to exclusion regions for all Desktop tasks. */
    fun setExclusionRegionListener(regionListener: Consumer<Region>, executor: Executor) {
        desktopGestureExclusionListener = regionListener
        desktopGestureExclusionExecutor = executor
        executor.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /** Creates a new merged region representative of all exclusion regions in all desktop tasks. */
    private fun calculateDesktopExclusionRegion(): Region {
        val desktopExclusionRegion = Region()
        desktopExclusionRegions.valueIterator().forEach { taskExclusionRegion ->
            desktopExclusionRegion.op(taskExclusionRegion, Region.Op.UNION)
        }
        return desktopExclusionRegion
    }

    /** Remove the previously registered [activeTasksListener] */
    fun removeActiveTasksListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.remove(activeTasksListener)
    }

    /** Removes the previously registered [visibleTasksListener]. */
    fun removeVisibleTasksListener(visibleTasksListener: VisibleTasksListener) {
        visibleTasksListeners.remove(visibleTasksListener)
    }

    /** Adds task with [taskId] to the list of active tasks on [displayId]. */
    fun addActiveTask(displayId: Int, taskId: Int) {
        // Removes task if it is active on another display excluding [displayId].
        removeActiveTask(taskId, excludedDisplayId = displayId)

        if (desktopTaskDataByDisplayId.getOrCreate(displayId).activeTasks.add(taskId)) {
            logD("Adds active task=%d displayId=%d", taskId, displayId)
            updateActiveTasksListeners(displayId)
        }
    }

    /** Removes task from active task list of displays excluding the [excludedDisplayId]. */
    fun removeActiveTask(taskId: Int, excludedDisplayId: Int? = null) {
        desktopTaskDataByDisplayId.forEach { displayId, desktopTaskData ->
            if ((displayId != excludedDisplayId)
                    && desktopTaskData.activeTasks.remove(taskId)) {
                logD("Removed active task=%d displayId=%d", taskId, displayId)
                updateActiveTasksListeners(displayId)
            }
        }
    }

    /** Adds given task to the closing task list for [displayId]. */
    fun addClosingTask(displayId: Int, taskId: Int) {
        if (desktopTaskDataByDisplayId.getOrCreate(displayId).closingTasks.add(taskId)) {
            logD("Added closing task=%d displayId=%d", taskId, displayId)
        } else {
            // If the task hasn't been removed from closing list after it disappeared.
            logW("Task with taskId=%d displayId=%d is already closing", taskId, displayId)
        }
    }

    /** Removes task from the list of closing tasks for [displayId]. */
    fun removeClosingTask(taskId: Int) {
        desktopTaskDataByDisplayId.forEach { displayId, taskInfo ->
            if (taskInfo.closingTasks.remove(taskId)) {
                logD("Removed closing task=%d displayId=%d", taskId, displayId)
            }
        }
    }

    fun isActiveTask(taskId: Int) = desktopTaskDataSequence().any { taskId in it.activeTasks }
    fun isClosingTask(taskId: Int) = desktopTaskDataSequence().any { taskId in it.closingTasks }
    fun isVisibleTask(taskId: Int) = desktopTaskDataSequence().any { taskId in it.visibleTasks }
    fun isMinimizedTask(taskId: Int) = desktopTaskDataSequence().any { taskId in it.minimizedTasks }

    /** Checks if a task is the only visible, non-closing, non-minimized task on its display. */
    fun isOnlyVisibleNonClosingTask(taskId: Int): Boolean =
        desktopTaskDataSequence().any { it.visibleTasks
            .subtract(it.closingTasks)
            .subtract(it.minimizedTasks)
            .singleOrNull() == taskId
        }

    fun getActiveTasks(displayId: Int): ArraySet<Int> =
        ArraySet(desktopTaskDataByDisplayId[displayId]?.activeTasks)

    fun getMinimizedTasks(displayId: Int): ArraySet<Int> =
        ArraySet(desktopTaskDataByDisplayId[displayId]?.minimizedTasks)

    /** Returns all active non-minimized tasks for [displayId] ordered from top to bottom. */
    fun getActiveNonMinimizedOrderedTasks(displayId: Int): List<Int> =
        getFreeformTasksInZOrder(displayId).filter { !isMinimizedTask(it) }

    /** Returns the count of active non-minimized tasks for [displayId]. */
    fun getActiveNonMinimizedTaskCount(displayId: Int): Int {
        return getActiveTasks(displayId).count { !isMinimizedTask(it) }
    }

    /** Returns a list of freeform tasks, ordered from top-bottom (top at index 0). */
    fun getFreeformTasksInZOrder(displayId: Int): ArrayList<Int> =
        ArrayList(desktopTaskDataByDisplayId[displayId]?.freeformTasksInZOrder ?: emptyList())

    /** Removes task from visible tasks of all displays except [excludedDisplayId]. */
    private fun removeVisibleTask(taskId: Int, excludedDisplayId: Int? = null) {
        desktopTaskDataByDisplayId.forEach { displayId, data ->
            if ((displayId != excludedDisplayId) && data.visibleTasks.remove(taskId)) {
                notifyVisibleTaskListeners(displayId, data.visibleTasks.size)
            }
        }
    }

    /**
     * Updates visibility of a freeform task with [taskId] on [displayId] and notifies listeners.
     *
     * If task was visible on a different display with a different [displayId], removes from
     * the set of visible tasks on that display and notifies listeners.
     */
    fun updateTaskVisibility(displayId: Int, taskId: Int, visible: Boolean) {
        if (visible) {
            // If task is visible, remove it from any other display besides [displayId].
            removeVisibleTask(taskId, excludedDisplayId = displayId)
        } else if (displayId == INVALID_DISPLAY) {
            // Task has vanished. Check which display to remove the task from.
            removeVisibleTask(taskId)
            return
        }
        val prevCount = getVisibleTaskCount(displayId)
        if (visible) {
            desktopTaskDataByDisplayId.getOrCreate(displayId).visibleTasks.add(taskId)
            unminimizeTask(displayId, taskId)
        } else {
            desktopTaskDataByDisplayId[displayId]?.visibleTasks?.remove(taskId)
        }
        val newCount = getVisibleTaskCount(displayId)
        if (prevCount != newCount) {
            logD("Update task visibility taskId=%d visible=%b displayId=%d",
                taskId, visible, displayId)
            logD("VisibleTaskCount has changed from %d to %d", prevCount, newCount)
            notifyVisibleTaskListeners(displayId, newCount)
        }
    }

    private fun notifyVisibleTaskListeners(displayId: Int, visibleTasksCount: Int) {
        visibleTasksListeners.forEach { (listener, executor) ->
            executor.execute { listener.onTasksVisibilityChanged(displayId, visibleTasksCount) }
        }
    }

    /** Gets number of visible tasks on given [displayId] */
    fun getVisibleTaskCount(displayId: Int): Int =
        desktopTaskDataByDisplayId[displayId]?.visibleTasks?.size ?: 0.also {
            logD("getVisibleTaskCount=$it")
        }

    /**
     * Adds task (or moves if it already exists) to the top of the ordered list.
     *
     * Unminimizes the task if it is minimized.
     */
    fun addOrMoveFreeformTaskToTop(displayId: Int, taskId: Int) {
        logD("Add or move task to top: display=%d taskId=%d", taskId, displayId)
        desktopTaskDataByDisplayId[displayId]?.freeformTasksInZOrder?.remove(taskId)
        desktopTaskDataByDisplayId.getOrCreate(displayId).freeformTasksInZOrder.add(0, taskId)
        // Unminimize the task if it is minimized.
        unminimizeTask(displayId, taskId)
        if (Flags.enableDesktopWindowingPersistence()) {
            updatePersistentRepository(displayId)
        }
    }

    /** Minimizes the task for [taskId] and [displayId] */
    fun minimizeTask(displayId: Int, taskId: Int) {
        logD("Minimize Task: display=%d, task=%d", displayId, taskId)
        desktopTaskDataByDisplayId.getOrCreate(displayId).minimizedTasks.add(taskId)
        if (Flags.enableDesktopWindowingPersistence()) {
            updatePersistentRepository(displayId)
        }
    }

    /** Unminimizes the task for [taskId] and [displayId] */
    fun unminimizeTask(displayId: Int, taskId: Int) {
        logD("Unminimize Task: display=%d, task=%d", displayId, taskId)
        desktopTaskDataByDisplayId[displayId]?.minimizedTasks?.remove(taskId) ?:
            logW("Unminimize Task: display=%d, task=%d, no task data", displayId, taskId)
    }

    private fun getDisplayIdForTask(taskId: Int): Int? {
        desktopTaskDataByDisplayId.forEach { displayId, data ->
            if (taskId in data.freeformTasksInZOrder) {
                return displayId
            }
        }
        logW("No display id found for task: taskId=%d", taskId)
        return null
    }

    /**
     * Removes [taskId] from the respective display. If [INVALID_DISPLAY], the original display id
     * will be looked up from the task id.
     */
    fun removeFreeformTask(displayId: Int, taskId: Int) {
        logD("Removes freeform task: taskId=%d", taskId)
        if (displayId == INVALID_DISPLAY) {
            // Removes the original display id of the task.
            getDisplayIdForTask(taskId)?.let { removeTaskFromDisplay(it, taskId) }
        } else {
            removeTaskFromDisplay(displayId, taskId)
        }
    }

    /** Removes given task from a valid [displayId] and updates the repository state. */
    private fun removeTaskFromDisplay(displayId: Int, taskId: Int) {
        logD("Removes freeform task: taskId=%d, displayId=%d", taskId, displayId)
        desktopTaskDataByDisplayId[displayId]?.freeformTasksInZOrder?.remove(taskId)
        boundsBeforeMaximizeByTaskId.remove(taskId)
        logD("Remaining freeform tasks: %s",
            desktopTaskDataByDisplayId[displayId]?.freeformTasksInZOrder?.toDumpString())
        // Remove task from unminimized task if it is minimized.
        unminimizeTask(displayId, taskId)
        removeActiveTask(taskId)
        updateTaskVisibility(displayId, taskId, visible = false)
        if (Flags.enableDesktopWindowingPersistence()) {
            updatePersistentRepository(displayId)
        }
    }

    /**
     * Updates active desktop gesture exclusion regions.
     *
     * If [desktopExclusionRegions] is accepted by [desktopGestureExclusionListener], updates it in
     * appropriate classes.
     */
    fun updateTaskExclusionRegions(taskId: Int, taskExclusionRegions: Region) {
        desktopExclusionRegions.put(taskId, taskExclusionRegions)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /**
     * Removes desktop gesture exclusion region for the specified task.
     *
     * If [desktopExclusionRegions] is accepted by [desktopGestureExclusionListener], updates it in
     * appropriate classes.
     */
    fun removeExclusionRegion(taskId: Int) {
        desktopExclusionRegions.delete(taskId)
        desktopGestureExclusionExecutor?.execute {
            desktopGestureExclusionListener?.accept(calculateDesktopExclusionRegion())
        }
    }

    /** Removes and returns the bounds saved before maximizing the given task. */
    fun removeBoundsBeforeMaximize(taskId: Int): Rect? =
        boundsBeforeMaximizeByTaskId.removeReturnOld(taskId)

    /** Saves the bounds of the given task before maximizing. */
    fun saveBoundsBeforeMaximize(taskId: Int, bounds: Rect) =
        boundsBeforeMaximizeByTaskId.set(taskId, Rect(bounds))

    private fun updatePersistentRepository(displayId: Int) {
        // Create a deep copy of the data
        desktopTaskDataByDisplayId[displayId]?.deepCopy()?.let { desktopTaskDataByDisplayIdCopy ->
            mainCoroutineScope.launch {
                try {
                    persistentRepository.addOrUpdateDesktop(
                        visibleTasks = desktopTaskDataByDisplayIdCopy.visibleTasks,
                        minimizedTasks = desktopTaskDataByDisplayIdCopy.minimizedTasks,
                        freeformTasksInZOrder = desktopTaskDataByDisplayIdCopy.freeformTasksInZOrder
                    )
                } catch (exception: Exception) {
                    logE(
                        "An exception occurred while updating the persistent repository \n%s",
                        exception.stackTrace
                    )
                }
            }
        }
    }


    internal fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopModeTaskRepository")
        dumpDesktopTaskData(pw, innerPrefix)
        pw.println("${innerPrefix}activeTasksListeners=${activeTasksListeners.size}")
        pw.println("${innerPrefix}visibleTasksListeners=${visibleTasksListeners.size}")
    }

    private fun dumpDesktopTaskData(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        desktopTaskDataByDisplayId.forEach { displayId, data ->
            pw.println("${prefix}Display $displayId:")
            pw.println("${innerPrefix}activeTasks=${data.activeTasks.toDumpString()}")
            pw.println("${innerPrefix}visibleTasks=${data.visibleTasks.toDumpString()}")
            pw.println(
                "${innerPrefix}freeformTasksInZOrder=${data.freeformTasksInZOrder.toDumpString()}"
            )
        }
    }

    /** Listens to changes for active tasks in desktop mode. */
    interface ActiveTasksListener {
        fun onActiveTasksChanged(displayId: Int) {}
    }

    /** Listens to changes for visible tasks in desktop mode. */
    interface VisibleTasksListener {
        fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {}
    }

    private fun logD(msg: String, vararg arguments: Any?) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private fun logW(msg: String, vararg arguments: Any?) {
        ProtoLog.w(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    private fun logE(msg: String, vararg arguments: Any?) {
        ProtoLog.e(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        private const val TAG = "DesktopModeTaskRepository"
    }
}

private fun <T> Iterable<T>.toDumpString(): String =
    joinToString(separator = ", ", prefix = "[", postfix = "]")

