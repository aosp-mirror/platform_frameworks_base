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

import android.graphics.Rect
import android.graphics.Region
import android.util.ArrayMap
import android.util.ArraySet
import android.util.SparseArray
import android.view.Display.INVALID_DISPLAY
import android.window.DesktopModeFlags
import androidx.core.util.forEach
import androidx.core.util.valueIterator
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.annotations.ShellMainThread
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Tracks desktop data for Android Desktop Windowing. */
class DesktopRepository(
    private val persistentRepository: DesktopPersistentRepository,
    @ShellMainThread private val mainCoroutineScope: CoroutineScope,
    val userId: Int,
) {
    /** A display that supports desktops. */
    private data class DesktopDisplay(
        val displayId: Int,
        val orderedDesks: MutableSet<Desk> = mutableSetOf(),
        // TODO: b/389960283 - update on desk activation / deactivation.
        var activeDeskId: Int? = null,
    )

    /**
     * Task data tracked per desk.
     *
     * @property activeTasks task ids of active tasks currently or previously visible in the desk.
     *   Tasks become inactive when task closes or when the desk becomes inactive.
     * @property visibleTasks task ids for active freeform tasks that are currently visible. There
     *   might be other active tasks in a desk that are not visible.
     * @property minimizedTasks task ids for active freeform tasks that are currently minimized.
     * @property closingTasks task ids for tasks that are going to close, but are currently visible.
     * @property freeformTasksInZOrder list of current freeform task ids ordered from top to bottom
     * @property fullImmersiveTaskId the task id of the desk's task that is in full-immersive mode.
     * @property topTransparentFullscreenTaskId the task id of any current top transparent
     *   fullscreen task launched on top of the desk. Cleared when the transparent task is closed or
     *   sent to back. (top is at index 0).
     * @property pipTaskId the task id of PiP task entered while in Desktop Mode.
     * @property pipShouldKeepDesktopActive whether an active PiP window should keep the desk
     *   active. Only false when we are explicitly exiting Desktop Mode (via user action) while
     *   there is an active PiP window.
     */
    private data class Desk(
        val deskId: Int,
        val displayId: Int,
        val activeTasks: ArraySet<Int> = ArraySet(),
        val visibleTasks: ArraySet<Int> = ArraySet(),
        val minimizedTasks: ArraySet<Int> = ArraySet(),
        // TODO(b/332682201): Remove when the repository state is updated via TransitionObserver
        val closingTasks: ArraySet<Int> = ArraySet(),
        val freeformTasksInZOrder: ArrayList<Int> = ArrayList(),
        var fullImmersiveTaskId: Int? = null,
        var topTransparentFullscreenTaskId: Int? = null,
        var pipTaskId: Int? = null,
        // TODO: b/389960283 - consolidate this with [DesktopDisplay#activeDeskId].
        var pipShouldKeepDesktopActive: Boolean = true,
    ) {
        fun deepCopy(): Desk =
            Desk(
                deskId = deskId,
                displayId = displayId,
                activeTasks = ArraySet(activeTasks),
                visibleTasks = ArraySet(visibleTasks),
                minimizedTasks = ArraySet(minimizedTasks),
                closingTasks = ArraySet(closingTasks),
                freeformTasksInZOrder = ArrayList(freeformTasksInZOrder),
                fullImmersiveTaskId = fullImmersiveTaskId,
                topTransparentFullscreenTaskId = topTransparentFullscreenTaskId,
                pipTaskId = pipTaskId,
                pipShouldKeepDesktopActive = pipShouldKeepDesktopActive,
            )

        // TODO: b/362720497 - remove when multi-desktops is enabled where instances aren't
        //  reusable.
        fun clear() {
            activeTasks.clear()
            visibleTasks.clear()
            minimizedTasks.clear()
            closingTasks.clear()
            freeformTasksInZOrder.clear()
            fullImmersiveTaskId = null
            topTransparentFullscreenTaskId = null
            pipTaskId = null
            pipShouldKeepDesktopActive = true
        }
    }

    private val activeTasksListeners = ArraySet<ActiveTasksListener>()
    private val visibleTasksListeners = ArrayMap<VisibleTasksListener, Executor>()

    /* Tracks corner/caption regions of desktop tasks, used to determine gesture exclusion. */
    private val desktopExclusionRegions = SparseArray<Region>()

    /* Tracks last bounds of task before toggled to stable bounds. */
    private val boundsBeforeMaximizeByTaskId = SparseArray<Rect>()

    /* Tracks last bounds of task before it is minimized. */
    private val boundsBeforeMinimizeByTaskId = SparseArray<Rect>()

    /* Tracks last bounds of task before toggled to immersive state. */
    private val boundsBeforeFullImmersiveByTaskId = SparseArray<Rect>()

    /* Callback for when a pending PiP transition has been aborted. */
    private var onPipAbortedCallback: ((Int, Int) -> Unit)? = null

    private var desktopGestureExclusionListener: Consumer<Region>? = null
    private var desktopGestureExclusionExecutor: Executor? = null

    private val desktopData: DesktopData =
        if (Flags.enableMultipleDesktopsBackend()) {
            MultiDesktopData()
        } else {
            SingleDesktopData()
        }

    /** Adds [activeTasksListener] to be notified of updates to active tasks. */
    fun addActiveTaskListener(activeTasksListener: ActiveTasksListener) {
        activeTasksListeners.add(activeTasksListener)
    }

    /** Adds [visibleTasksListener] to be notified of updates to visible tasks. */
    fun addVisibleTasksListener(visibleTasksListener: VisibleTasksListener, executor: Executor) {
        visibleTasksListeners[visibleTasksListener] = executor
        desktopData
            .desksSequence()
            .groupBy { it.displayId }
            .keys
            .forEach { displayId ->
                val visibleTaskCount = getVisibleTaskCount(displayId)
                executor.execute {
                    visibleTasksListener.onTasksVisibilityChanged(displayId, visibleTaskCount)
                }
            }
    }

    /** Updates tasks changes on all the active task listeners for given display id. */
    private fun updateActiveTasksListeners(displayId: Int) {
        activeTasksListeners.onEach { it.onActiveTasksChanged(displayId) }
    }

    /** Returns a list of all [Desk]s in the repository. */
    private fun desksSequence(): Sequence<Desk> = desktopData.desksSequence()

    /** Returns the number of desks in the given display. */
    fun getNumberOfDesks(displayId: Int) = desktopData.getNumberOfDesks(displayId)

    /** Returns the display the given desk is in. */
    fun getDisplayForDesk(deskId: Int) = desktopData.getDisplayForDesk(deskId)

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

    /** Adds the given desk under the given display. */
    fun addDesk(displayId: Int, deskId: Int) {
        desktopData.createDesk(displayId, deskId)
    }

    /** Returns the ids of the existing desks in the given display. */
    @VisibleForTesting
    fun getDeskIds(displayId: Int): Set<Int> =
        desktopData.desksSequence(displayId).map { desk -> desk.deskId }.toSet()

    /** Returns the id of the default desk in the given display. */
    fun getDefaultDeskId(displayId: Int): Int? = getDefaultDesk(displayId)?.deskId

    /** Returns the default desk in the given display. */
    private fun getDefaultDesk(displayId: Int): Desk? = desktopData.getDefaultDesk(displayId)

    /** Sets the given desk as the active one in the given display. */
    fun setActiveDesk(displayId: Int, deskId: Int) {
        desktopData.setActiveDesk(displayId = displayId, deskId = deskId)
    }

    /**
     * Adds task with [taskId] to the list of freeform tasks on [displayId]'s active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun addTask(displayId: Int, taskId: Int, isVisible: Boolean) {
        addOrMoveFreeformTaskToTop(displayId, taskId)
        addActiveTask(displayId, taskId)
        updateTask(displayId, taskId, isVisible)
    }

    /**
     * Adds task with [taskId] to the list of active tasks on [displayId]'s active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    private fun addActiveTask(displayId: Int, taskId: Int) {
        val activeDesk = desktopData.getDefaultDesk(displayId)
        checkNotNull(activeDesk) { "Expected desk in display: $displayId" }

        // Removes task if it is active on another desk excluding [activeDesk].
        removeActiveTask(taskId, excludedDeskId = activeDesk.deskId)

        if (activeDesk.activeTasks.add(taskId)) {
            logD("Adds active task=%d displayId=%d deskId=%d", taskId, displayId, activeDesk.deskId)
            updateActiveTasksListeners(displayId)
        }
    }

    /** Removes task from active task list of desks excluding the [excludedDeskId]. */
    @VisibleForTesting
    fun removeActiveTask(taskId: Int, excludedDeskId: Int? = null) {
        val affectedDisplays = mutableSetOf<Int>()
        desktopData.forAllDesks { displayId, desk ->
            if (desk.deskId != excludedDeskId && desk.activeTasks.remove(taskId)) {
                logD(
                    "Removed active task=%d displayId=%d deskId=%d",
                    taskId,
                    displayId,
                    desk.deskId,
                )
                affectedDisplays.add(displayId)
            }
        }
        affectedDisplays.forEach { displayId -> updateActiveTasksListeners(displayId) }
    }

    /**
     * Adds given task to the closing task list for [displayId]'s active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun addClosingTask(displayId: Int, taskId: Int) {
        val activeDesk =
            desktopData.getActiveDesk(displayId)
                ?: error("Expected active desk in display: $displayId")
        if (activeDesk.closingTasks.add(taskId)) {
            logD(
                "Added closing task=%d displayId=%d deskId=%d",
                taskId,
                displayId,
                activeDesk.deskId,
            )
        } else {
            // If the task hasn't been removed from closing list after it disappeared.
            logW(
                "Task with taskId=%d displayId=%d deskId=%d is already closing",
                taskId,
                displayId,
                activeDesk.deskId,
            )
        }
    }

    /** Removes task from the list of closing tasks for all desks. */
    fun removeClosingTask(taskId: Int) {
        desktopData.forAllDesks { desk ->
            if (desk.closingTasks.remove(taskId)) {
                logD("Removed closing task=%d deskId=%d", taskId, desk.deskId)
            }
        }
    }

    fun isActiveTask(taskId: Int) = desksSequence().any { taskId in it.activeTasks }

    fun isClosingTask(taskId: Int) = desksSequence().any { taskId in it.closingTasks }

    fun isVisibleTask(taskId: Int) = desksSequence().any { taskId in it.visibleTasks }

    fun isMinimizedTask(taskId: Int) = desksSequence().any { taskId in it.minimizedTasks }

    /**
     * Checks if a task is the only visible, non-closing, non-minimized task on the active desk of
     * the given display, or any display's active desk if [displayId] is [INVALID_DISPLAY].
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun isOnlyVisibleNonClosingTask(taskId: Int, displayId: Int = INVALID_DISPLAY): Boolean {
        val activeDesks =
            if (displayId != INVALID_DISPLAY) {
                setOfNotNull(desktopData.getActiveDesk(displayId))
            } else {
                desktopData.getAllActiveDesks()
            }
        return activeDesks.any { desk ->
            desk.visibleTasks
                .subtract(desk.closingTasks)
                .subtract(desk.minimizedTasks)
                .singleOrNull() == taskId
        }
    }

    /**
     * Returns the active tasks in the given display's active desk.
     *
     * TODO: b/389960283 - migrate callers to [getActiveTaskIdsInDesk].
     */
    @VisibleForTesting
    fun getActiveTasks(displayId: Int): ArraySet<Int> =
        ArraySet(desktopData.getActiveDesk(displayId)?.activeTasks)

    /**
     * Returns the minimized tasks in the given display's active desk.
     *
     * TODO: b/389960283 - migrate callers to [getMinimizedTaskIdsInDesk].
     */
    fun getMinimizedTasks(displayId: Int): ArraySet<Int> =
        ArraySet(desktopData.getActiveDesk(displayId)?.minimizedTasks)

    @VisibleForTesting
    fun getMinimizedTaskIdsInDesk(deskId: Int): ArraySet<Int> =
        ArraySet(desktopData.getDesk(deskId)?.minimizedTasks)

    /**
     * Returns all active non-minimized tasks for [displayId] ordered from top to bottom.
     *
     * TODO: b/389960283 - migrate callers to [getExpandedTasksIdsInDeskOrdered].
     */
    fun getExpandedTasksOrdered(displayId: Int): List<Int> =
        getFreeformTasksInZOrder(displayId).filter { !isMinimizedTask(it) }

    @VisibleForTesting
    fun getExpandedTasksIdsInDeskOrdered(deskId: Int): List<Int> =
        getFreeformTasksIdsInDeskInZOrder(deskId).filter { !isMinimizedTask(it) }

    /**
     * Returns the count of active non-minimized tasks for [displayId].
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun getExpandedTaskCount(displayId: Int): Int {
        return getActiveTasks(displayId).count { !isMinimizedTask(it) }
    }

    /**
     * Returns a list of freeform tasks, ordered from top-bottom (top at index 0).
     *
     * TODO: b/389960283 - migrate callers to [getFreeformTasksIdsInDeskInZOrder].
     */
    @VisibleForTesting
    fun getFreeformTasksInZOrder(displayId: Int): ArrayList<Int> =
        ArrayList(desktopData.getDefaultDesk(displayId)?.freeformTasksInZOrder ?: emptyList())

    @VisibleForTesting
    fun getFreeformTasksIdsInDeskInZOrder(deskId: Int): ArrayList<Int> =
        ArrayList(desktopData.getDesk(deskId)?.freeformTasksInZOrder ?: emptyList())

    /** Returns the tasks inside the given desk. */
    fun getActiveTaskIdsInDesk(deskId: Int): Set<Int> =
        desktopData.getDesk(deskId)?.activeTasks?.toSet()
            ?: run {
                logW("getTasksInDesk: could not find desk: deskId=%d", deskId)
                emptySet()
            }

    /** Removes task from visible tasks of all displays except [excludedDisplayId]. */
    private fun removeVisibleTask(taskId: Int, excludedDisplayId: Int? = null) {
        desktopData.forAllDesks { displayId, desk ->
            if (displayId != excludedDisplayId && desk.visibleTasks.remove(taskId)) {
                notifyVisibleTaskListeners(displayId, desk.visibleTasks.size)
            }
        }
    }

    /**
     * Updates visibility of a freeform task with [taskId] on [displayId] and notifies listeners.
     *
     * If task was visible on a different display with a different [displayId], removes from the set
     * of visible tasks on that display and notifies listeners.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun updateTask(displayId: Int, taskId: Int, isVisible: Boolean) {
        logD("updateTask taskId=%d, displayId=%d, isVisible=%b", taskId, displayId, isVisible)

        if (isVisible) {
            // If task is visible, remove it from any other display besides [displayId].
            removeVisibleTask(taskId, excludedDisplayId = displayId)
        } else if (displayId == INVALID_DISPLAY) {
            // Task has vanished. Check which display to remove the task from.
            removeVisibleTask(taskId)
            return
        }
        val prevCount = getVisibleTaskCount(displayId)
        if (isVisible) {
            desktopData.getDefaultDesk(displayId)?.visibleTasks?.add(taskId)
                ?: error("Expected non-null desk in display $displayId")
            unminimizeTask(displayId, taskId)
        } else {
            desktopData.getActiveDesk(displayId)?.visibleTasks?.remove(taskId)
        }
        val newCount = getVisibleTaskCount(displayId)
        if (prevCount != newCount) {
            logD(
                "Update task visibility taskId=%d visible=%b displayId=%d",
                taskId,
                isVisible,
                displayId,
            )
            logD("VisibleTaskCount has changed from %d to %d", prevCount, newCount)
            notifyVisibleTaskListeners(displayId, newCount)
            if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) {
                updatePersistentRepository(displayId)
            }
        }
    }

    /**
     * Set whether the given task is the Desktop-entered PiP task in this display's active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun setTaskInPip(displayId: Int, taskId: Int, enterPip: Boolean) {
        val activeDesk =
            desktopData.getActiveDesk(displayId)
                ?: error("Expected active desk in display: $displayId")
        if (enterPip) {
            activeDesk.pipTaskId = taskId
            activeDesk.pipShouldKeepDesktopActive = true
        } else {
            activeDesk.pipTaskId =
                if (activeDesk.pipTaskId == taskId) null
                else {
                    logW(
                        "setTaskInPip: taskId=%d did not match saved taskId=%d",
                        taskId,
                        activeDesk.pipTaskId,
                    )
                    activeDesk.pipTaskId
                }
        }
        notifyVisibleTaskListeners(displayId, getVisibleTaskCount(displayId))
    }

    /**
     * Returns whether there is a PiP that was entered/minimized from Desktop in this display's
     * active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun isMinimizedPipPresentInDisplay(displayId: Int): Boolean =
        desktopData.getActiveDesk(displayId)?.pipTaskId != null

    /**
     * Returns whether the given task is the Desktop-entered PiP task in this display's active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun isTaskMinimizedPipInDisplay(displayId: Int, taskId: Int): Boolean =
        desktopData.getActiveDesk(displayId)?.pipTaskId == taskId

    /**
     * Returns whether a desk should be active in this display due to active PiP.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun shouldDesktopBeActiveForPip(displayId: Int): Boolean =
        Flags.enableDesktopWindowingPip() &&
            isMinimizedPipPresentInDisplay(displayId) &&
            (desktopData.getActiveDesk(displayId)?.pipShouldKeepDesktopActive ?: false)

    /**
     * Saves whether a PiP window should keep Desktop session active in this display.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun setPipShouldKeepDesktopActive(displayId: Int, keepActive: Boolean) {
        desktopData.getActiveDesk(displayId)?.pipShouldKeepDesktopActive = keepActive
    }

    /**
     * Saves callback to handle a pending PiP transition being aborted.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun setOnPipAbortedCallback(callbackIfPipAborted: ((displayId: Int, pipTaskId: Int) -> Unit)?) {
        onPipAbortedCallback = callbackIfPipAborted
    }

    /**
     * Invokes callback to handle a pending PiP transition with the given task id being aborted.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun onPipAborted(displayId: Int, pipTaskId: Int) {
        onPipAbortedCallback?.invoke(displayId, pipTaskId)
    }

    /**
     * Set whether the given task is the full-immersive task in this display's active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun setTaskInFullImmersiveState(displayId: Int, taskId: Int, immersive: Boolean) {
        val desktopData = desktopData.getActiveDesk(displayId) ?: return
        if (immersive) {
            desktopData.fullImmersiveTaskId = taskId
        } else {
            if (desktopData.fullImmersiveTaskId == taskId) {
                desktopData.fullImmersiveTaskId = null
            }
        }
    }

    /* Whether the task is in full-immersive state. */
    fun isTaskInFullImmersiveState(taskId: Int): Boolean {
        return desksSequence().any { taskId == it.fullImmersiveTaskId }
    }

    /**
     * Returns the task that is currently in immersive mode in this display, or null.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun getTaskInFullImmersiveState(displayId: Int): Int? =
        desktopData.getActiveDesk(displayId)?.fullImmersiveTaskId

    /**
     * Sets the top transparent fullscreen task id for a given display's active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun setTopTransparentFullscreenTaskId(displayId: Int, taskId: Int) {
        desktopData.getActiveDesk(displayId)?.topTransparentFullscreenTaskId = taskId
    }

    /**
     * Returns the top transparent fullscreen task id for a given display's active desk, or null.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun getTopTransparentFullscreenTaskId(displayId: Int): Int? =
        desktopData.getActiveDesk(displayId)?.topTransparentFullscreenTaskId

    /**
     * Clears the top transparent fullscreen task id info for a given display's active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun clearTopTransparentFullscreenTaskId(displayId: Int) {
        desktopData.getActiveDesk(displayId)?.topTransparentFullscreenTaskId = null
    }

    private fun notifyVisibleTaskListeners(displayId: Int, visibleTasksCount: Int) {
        val visibleAndPipTasksCount =
            if (shouldDesktopBeActiveForPip(displayId)) visibleTasksCount + 1 else visibleTasksCount
        visibleTasksListeners.forEach { (listener, executor) ->
            executor.execute {
                listener.onTasksVisibilityChanged(displayId, visibleAndPipTasksCount)
            }
        }
    }

    /**
     * Gets number of visible freeform tasks on given [displayId]'s active desk.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun getVisibleTaskCount(displayId: Int): Int =
        (desktopData.getActiveDesk(displayId)?.visibleTasks?.size ?: 0).also {
            logD("getVisibleTaskCount=$it")
        }

    /**
     * Adds task (or moves if it already exists) to the top of the ordered list.
     *
     * Unminimizes the task if it is minimized.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    private fun addOrMoveFreeformTaskToTop(displayId: Int, taskId: Int) {
        val desk = getDefaultDesk(displayId) ?: error("Expected a desk in display: $displayId")
        logD(
            "Add or move task to top: display=%d taskId=%d deskId=%d",
            taskId,
            displayId,
            desk.deskId,
        )
        desktopData.forAllDesks { _, desk1 -> desk1.freeformTasksInZOrder.remove(taskId) }
        desk.freeformTasksInZOrder.add(0, taskId)
        // Unminimize the task if it is minimized.
        unminimizeTask(displayId, taskId)
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) {
            updatePersistentRepository(displayId)
        }
    }

    /**
     * Minimizes the task for [taskId] and [displayId]'s active display.
     *
     * TODO: b/389960283 - add explicit [deskId] argument.
     */
    fun minimizeTask(displayId: Int, taskId: Int) {
        if (displayId == INVALID_DISPLAY) {
            // When a task vanishes it doesn't have a displayId. Find the display of the task and
            // mark it as minimized.
            getDisplayIdForTask(taskId)?.let { minimizeTask(it, taskId) }
                ?: logW("Minimize task: No display id found for task: taskId=%d", taskId)
        } else {
            logD("Minimize Task: display=%d, task=%d", displayId, taskId)
            desktopData.getActiveDesk(displayId)?.minimizedTasks?.add(taskId)
                ?: logD("Minimize task: No active desk found for task: taskId=%d", taskId)
        }
        updateTask(displayId, taskId, isVisible = false)
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) {
            updatePersistentRepository(displayId)
        }
    }

    /**
     * Unminimizes the task for [taskId] and [displayId].
     *
     * TODO: b/389960283 - consider adding an explicit [deskId] argument.
     */
    fun unminimizeTask(displayId: Int, taskId: Int) {
        logD("Unminimize Task: display=%d, task=%d", displayId, taskId)
        var removed = false
        desktopData.forAllDesks(displayId) { desk ->
            if (desk.minimizedTasks.remove(taskId)) {
                removed = true
            }
        }
        if (!removed) {
            logW("Unminimize Task: display=%d, task=%d, no task data", displayId, taskId)
        }
    }

    private fun getDisplayIdForTask(taskId: Int): Int? {
        var displayForTask: Int? = null
        desktopData.forAllDesks { displayId, desk ->
            if (taskId in desk.freeformTasksInZOrder) {
                displayForTask = displayId
            }
        }
        if (displayForTask == null) {
            logW("No display id found for task: taskId=%d", taskId)
        }
        return displayForTask
    }

    /**
     * Removes [taskId] from the respective display. If [INVALID_DISPLAY], the original display id
     * will be looked up from the task id.
     *
     * TODO: b/389960283 - consider adding an explicit [deskId] argument.
     */
    fun removeTask(displayId: Int, taskId: Int) {
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
        desktopData.forAllDesks(displayId) { desk ->
            if (desk.freeformTasksInZOrder.remove(taskId)) {
                logD(
                    "Remaining freeform tasks in desk: %d, tasks: %s",
                    desk.deskId,
                    desk.freeformTasksInZOrder.toDumpString(),
                )
            }
        }
        boundsBeforeMaximizeByTaskId.remove(taskId)
        boundsBeforeFullImmersiveByTaskId.remove(taskId)
        // Remove task from unminimized task if it is minimized.
        unminimizeTask(displayId, taskId)
        // Mark task as not in immersive if it was immersive.
        setTaskInFullImmersiveState(displayId = displayId, taskId = taskId, immersive = false)
        removeActiveTask(taskId)
        removeVisibleTask(taskId)
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) {
            updatePersistentRepository(displayId)
        }
    }

    /** Removes the given desk and returns the active tasks in that desk. */
    fun removeDesk(deskId: Int): Set<Int> {
        val desk =
            desktopData.getDesk(deskId)
                ?: return emptySet<Int>().also {
                    logW("Could not find desk to remove: deskId=%d", deskId)
                }
        val activeTasks = ArraySet(desk.activeTasks)
        desktopData.remove(desk.deskId)
        return activeTasks
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

    /** Removes and returns the bounds saved before minimizing the given task. */
    fun removeBoundsBeforeMinimize(taskId: Int): Rect? =
        boundsBeforeMinimizeByTaskId.removeReturnOld(taskId)

    /** Saves the bounds of the given task before minimizing. */
    fun saveBoundsBeforeMinimize(taskId: Int, bounds: Rect?) =
        boundsBeforeMinimizeByTaskId.set(taskId, Rect(bounds))

    /** Removes and returns the bounds saved before entering immersive with the given task. */
    fun removeBoundsBeforeFullImmersive(taskId: Int): Rect? =
        boundsBeforeFullImmersiveByTaskId.removeReturnOld(taskId)

    /** Saves the bounds of the given task before entering immersive. */
    fun saveBoundsBeforeFullImmersive(taskId: Int, bounds: Rect) =
        boundsBeforeFullImmersiveByTaskId.set(taskId, Rect(bounds))

    /** TODO: b/389960283 - consider updating only the changing desks. */
    private fun updatePersistentRepository(displayId: Int) {
        val desks = desktopData.desksSequence(displayId).map { desk -> desk.deepCopy() }.toList()
        mainCoroutineScope.launch {
            desks.forEach { desk ->
                try {
                    persistentRepository.addOrUpdateDesktop(
                        // Use display id as desk id for now since only once desk per display
                        // is supported.
                        userId = userId,
                        desktopId = desk.deskId,
                        visibleTasks = desk.visibleTasks,
                        minimizedTasks = desk.minimizedTasks,
                        freeformTasksInZOrder = desk.freeformTasksInZOrder,
                    )
                } catch (exception: Exception) {
                    logE(
                        "An exception occurred while updating the persistent repository \n%s",
                        exception.stackTrace,
                    )
                }
            }
        }
    }

    internal fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopRepository")
        dumpDesktopTaskData(pw, innerPrefix)
        pw.println("${innerPrefix}activeTasksListeners=${activeTasksListeners.size}")
        pw.println("${innerPrefix}visibleTasksListeners=${visibleTasksListeners.size}")
    }

    private fun dumpDesktopTaskData(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        desktopData
            .desksSequence()
            .groupBy { it.displayId }
            .forEach { (displayId, desks) ->
                pw.println("${prefix}Display #$displayId:")
                desks.forEach { desk ->
                    pw.println("${innerPrefix}Desk #${desk.deskId}:")
                    pw.print("$innerPrefix  activeTasks=")
                    pw.println(desk.activeTasks.toDumpString())
                    pw.print("$innerPrefix  visibleTasks=")
                    pw.println(desk.visibleTasks.toDumpString())
                    pw.print("$innerPrefix  freeformTasksInZOrder=")
                    pw.println(desk.freeformTasksInZOrder.toDumpString())
                    pw.print("$innerPrefix  minimizedTasks=")
                    pw.println(desk.minimizedTasks.toDumpString())
                    pw.print("$innerPrefix  fullImmersiveTaskId=")
                    pw.println(desk.fullImmersiveTaskId)
                    pw.print("$innerPrefix  topTransparentFullscreenTaskId=")
                    pw.println(desk.topTransparentFullscreenTaskId)
                }
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

    /** An interface for the desktop hierarchy's data managed by this repository. */
    private interface DesktopData {
        /** Creates a desk record. */
        fun createDesk(displayId: Int, deskId: Int)

        /** Returns the desk with the given id, or null if it does not exist. */
        fun getDesk(deskId: Int): Desk?

        /** Returns the active desk in this diplay, or null if none are active. */
        fun getActiveDesk(displayId: Int): Desk?

        /** Sets the given desk as the active desk in the given display. */
        fun setActiveDesk(displayId: Int, deskId: Int)

        /**
         * Returns the default desk in the given display. Useful when the system wants to activate a
         * desk but doesn't care about which one it activates (e.g. when putting a window into a
         * desk using the App Handle). May return null if the display does not support desks.
         *
         * TODO: 389787966 - consider removing or renaming. In practice, this is needed for
         *   soon-to-be deprecated IDesktopMode APIs, adb commands or entry-points into the only
         *   desk (single-desk devices) or the most-recent desk (multi-desk devices).
         */
        fun getDefaultDesk(displayId: Int): Desk?

        /** Returns all the active desks of all displays. */
        fun getAllActiveDesks(): Set<Desk>

        /** Returns the number of desks in the given display. */
        fun getNumberOfDesks(displayId: Int): Int

        /** Applies a function to all desks. */
        fun forAllDesks(consumer: (Desk) -> Unit)

        /** Applies a function to all desks. */
        fun forAllDesks(consumer: (displayId: Int, Desk) -> Unit)

        /** Applies a function to all desks under the given display. */
        fun forAllDesks(displayId: Int, consumer: (Desk) -> Unit)

        /** Returns a sequence of all desks. */
        fun desksSequence(): Sequence<Desk>

        /** Returns a sequence of all desks under the given display. */
        fun desksSequence(displayId: Int): Sequence<Desk>

        /** Remove an existing desk if it exists. */
        fun remove(deskId: Int)

        /** Returns the id of the display where the given desk is located. */
        fun getDisplayForDesk(deskId: Int): Int
    }

    /**
     * A [DesktopData] implementation that only supports one desk per display.
     *
     * Internally, it reuses the displayId as that display's single desk's id. It also never truly
     * "removes" a desk, it just clears its content.
     */
    private class SingleDesktopData : DesktopData {
        private val deskByDisplayId =
            object : SparseArray<Desk>() {
                /** Gets [Desk] for existing [displayId] or creates a new one. */
                fun getOrCreate(displayId: Int): Desk =
                    this[displayId]
                        ?: Desk(deskId = displayId, displayId = displayId).also {
                            this[displayId] = it
                        }
            }

        override fun createDesk(displayId: Int, deskId: Int) {
            check(displayId == deskId) { "Display and desk ids must match" }
            deskByDisplayId.getOrCreate(displayId)
        }

        override fun getDesk(deskId: Int): Desk =
            // TODO: b/362720497 - consider enforcing that the desk has been created before trying
            //  to use it. As of now, there are cases where a task may be created faster than a
            //  desk is, so just create it here if needed. See b/391984373.
            deskByDisplayId.getOrCreate(deskId)

        override fun getActiveDesk(displayId: Int): Desk {
            // TODO: 389787966 - consider migrating to an "active" state instead of checking the
            //   number of visible active tasks, PIP in desktop, and empty desktop logic. In
            //   practice, existing single-desktop devices are ok with this function returning the
            //   only desktop, even if it's not active.
            return deskByDisplayId.getOrCreate(displayId)
        }

        override fun setActiveDesk(displayId: Int, deskId: Int) {
            // No-op, in single-desk setups, which desktop is "active" is determined by the
            // existence of visible desktop windows, among other factors.
        }

        override fun getDefaultDesk(displayId: Int): Desk = getDesk(deskId = displayId)

        override fun getAllActiveDesks(): Set<Desk> =
            deskByDisplayId.valueIterator().asSequence().toSet()

        override fun getNumberOfDesks(displayId: Int): Int = 1

        override fun forAllDesks(consumer: (Desk) -> Unit) {
            deskByDisplayId.forEach { _, desk -> consumer(desk) }
        }

        override fun forAllDesks(consumer: (Int, Desk) -> Unit) {
            deskByDisplayId.forEach { displayId, desk -> consumer(displayId, desk) }
        }

        override fun forAllDesks(displayId: Int, consumer: (Desk) -> Unit) {
            consumer(getDesk(deskId = displayId))
        }

        override fun desksSequence(): Sequence<Desk> = deskByDisplayId.valueIterator().asSequence()

        override fun desksSequence(displayId: Int): Sequence<Desk> =
            deskByDisplayId[displayId]?.let { sequenceOf(it) } ?: emptySequence()

        override fun remove(deskId: Int) {
            deskByDisplayId[deskId]?.clear()
        }

        override fun getDisplayForDesk(deskId: Int): Int = deskId
    }

    /** A [DesktopData] implementation that supports multiple desks. */
    private class MultiDesktopData : DesktopData {
        private val desktopDisplays = SparseArray<DesktopDisplay>()

        override fun createDesk(displayId: Int, deskId: Int) {
            val display =
                desktopDisplays[displayId]
                    ?: DesktopDisplay(displayId).also { desktopDisplays[displayId] = it }
            check(display.orderedDesks.none { desk -> desk.deskId == deskId }) {
                "Attempting to create desk#$deskId that already exists in display#$displayId"
            }
            display.orderedDesks.add(Desk(deskId = deskId, displayId = displayId))
        }

        override fun getDesk(deskId: Int): Desk? {
            desktopDisplays.forEach { _, display ->
                val desk = display.orderedDesks.find { desk -> desk.deskId == deskId }
                if (desk != null) {
                    return desk
                }
            }
            return null
        }

        override fun getActiveDesk(displayId: Int): Desk? {
            val display = desktopDisplays[displayId] ?: return null
            if (display.activeDeskId == null) return null
            return display.orderedDesks.find { it.deskId == display.activeDeskId }
        }

        override fun setActiveDesk(displayId: Int, deskId: Int) {
            val display =
                desktopDisplays[displayId] ?: error("Expected display#$displayId to exist")
            val desk = display.orderedDesks.single { it.deskId == deskId }
            display.activeDeskId = desk.deskId
        }

        override fun getDefaultDesk(displayId: Int): Desk? {
            val display = desktopDisplays[displayId] ?: return null
            return display.orderedDesks.find { it.deskId == display.activeDeskId }
                ?: display.orderedDesks.firstOrNull()
        }

        override fun getAllActiveDesks(): Set<Desk> {
            return desktopDisplays
                .valueIterator()
                .asSequence()
                .filter { display -> display.activeDeskId != null }
                .map { display ->
                    display.orderedDesks.single { it.deskId == display.activeDeskId }
                }
                .toSet()
        }

        override fun getNumberOfDesks(displayId: Int): Int =
            desktopDisplays[displayId]?.orderedDesks?.size ?: 0

        override fun forAllDesks(consumer: (Desk) -> Unit) {
            desktopDisplays.forEach { _, display -> display.orderedDesks.forEach { consumer(it) } }
        }

        override fun forAllDesks(consumer: (Int, Desk) -> Unit) {
            desktopDisplays.forEach { _, display ->
                display.orderedDesks.forEach { consumer(display.displayId, it) }
            }
        }

        override fun forAllDesks(displayId: Int, consumer: (Desk) -> Unit) {
            desktopDisplays
                .valueIterator()
                .asSequence()
                .filter { display -> display.displayId == displayId }
                .flatMap { display -> display.orderedDesks.asSequence() }
                .forEach { desk -> consumer(desk) }
        }

        override fun desksSequence(): Sequence<Desk> =
            desktopDisplays.valueIterator().asSequence().flatMap { display ->
                display.orderedDesks.asSequence()
            }

        override fun desksSequence(displayId: Int): Sequence<Desk> =
            desktopDisplays[displayId]?.orderedDesks?.asSequence() ?: emptySequence()

        override fun remove(deskId: Int) {
            desktopDisplays.forEach { _, display ->
                display.orderedDesks.removeIf { it.deskId == deskId }
            }
        }

        override fun getDisplayForDesk(deskId: Int): Int =
            desksSequence().find { it.deskId == deskId }?.displayId
                ?: error("Display for desk=$deskId not found")
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
        private const val TAG = "DesktopRepository"
    }
}

private fun <T> Iterable<T>.toDumpString(): String =
    joinToString(separator = ", ", prefix = "[", postfix = "]")
