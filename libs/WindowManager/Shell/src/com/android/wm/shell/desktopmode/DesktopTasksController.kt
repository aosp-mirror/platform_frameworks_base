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

import android.R
import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.IBinder
import android.os.SystemProperties
import android.util.DisplayMetrics.DENSITY_DEFAULT
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ExecutorUtils
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.LaunchAdjacentController
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SingleInstanceRemoteListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.annotations.ExternalThread
import com.android.wm.shell.common.annotations.ShellMainThread
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.util.KtProtoLog
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Handles moving tasks in and out of desktop */
class DesktopTasksController(
        private val context: Context,
        shellInit: ShellInit,
        private val shellCommandHandler: ShellCommandHandler,
        private val shellController: ShellController,
        private val displayController: DisplayController,
        private val shellTaskOrganizer: ShellTaskOrganizer,
        private val syncQueue: SyncTransactionQueue,
        private val rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
        private val transitions: Transitions,
        private val enterDesktopTaskTransitionHandler: EnterDesktopTaskTransitionHandler,
        private val exitDesktopTaskTransitionHandler: ExitDesktopTaskTransitionHandler,
        private val toggleResizeDesktopTaskTransitionHandler:
        ToggleResizeDesktopTaskTransitionHandler,
        private val desktopModeTaskRepository: DesktopModeTaskRepository,
        private val launchAdjacentController: LaunchAdjacentController,
        @ShellMainThread private val mainExecutor: ShellExecutor
) : RemoteCallable<DesktopTasksController>, Transitions.TransitionHandler {

    private val desktopMode: DesktopModeImpl
    private var visualIndicator: DesktopModeVisualIndicator? = null
    private val mOnAnimationFinishedCallback = Consumer<SurfaceControl.Transaction> {
        t: SurfaceControl.Transaction ->
        visualIndicator?.releaseVisualIndicator(t)
        visualIndicator = null
    }
    private val taskVisibilityListener = object : VisibleTasksListener {
        override fun onVisibilityChanged(displayId: Int, hasVisibleFreeformTasks: Boolean) {
            launchAdjacentController.launchAdjacentEnabled = !hasVisibleFreeformTasks
        }
    }

    private val transitionAreaHeight
        get() = context.resources.getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_height)

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.isProto2Enabled()) {
            shellInit.addInitCallback({ onInit() }, this)
        }
    }

    private fun onInit() {
        KtProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopTasksController")
        shellCommandHandler.addDumpCallback(this::dump, this)
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
        transitions.addHandler(this)
        desktopModeTaskRepository.addVisibleTasksListener(taskVisibilityListener, mainExecutor)
    }

    /** Show all tasks, that are part of the desktop, on top of launcher */
    fun showDesktopApps(displayId: Int) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: showDesktopApps")
        val wct = WindowContainerTransaction()
        // TODO(b/278084491): pass in display id
        bringDesktopAppsToFront(displayId, wct)

        // Execute transaction if there are pending operations
        if (!wct.isEmpty) {
            if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                // TODO(b/268662477): add animation for the transition
                transitions.startTransition(TRANSIT_NONE, wct, null /* handler */)
            } else {
                shellTaskOrganizer.applyTransaction(wct)
            }
        }
    }

    /**
     * Stash desktop tasks on display with id [displayId].
     *
     * When desktop tasks are stashed, launcher home screen icons are fully visible. New apps
     * launched in this state will be added to the desktop. Existing desktop tasks will be brought
     * back to front during the launch.
     */
    fun stashDesktopApps(displayId: Int) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: stashDesktopApps")
        desktopModeTaskRepository.setStashed(displayId, true)
    }

    /**
     * Clear the stashed state for the given display
     */
    fun hideStashedDesktopApps(displayId: Int) {
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: hideStashedApps displayId=%d",
                displayId
        )
        desktopModeTaskRepository.setStashed(displayId, false)
    }

    /** Get number of tasks that are marked as visible */
    fun getVisibleTaskCount(displayId: Int): Int {
        return desktopModeTaskRepository.getVisibleTaskCount(displayId)
    }

    /** Move a task with given `taskId` to desktop */
    fun moveToDesktop(taskId: Int, wct: WindowContainerTransaction = WindowContainerTransaction()) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let {
            task -> moveToDesktop(task, wct)
        }
    }

    /**
     * Move a task to desktop
     */
    fun moveToDesktop(
            task: RunningTaskInfo,
            wct: WindowContainerTransaction = WindowContainerTransaction()
    ) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToDesktop taskId=%d",
            task.taskId
        )
        // Bring other apps to front first
        bringDesktopAppsToFront(task.displayId, wct)
        addMoveToDesktopChanges(wct, task)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Moves a single task to freeform and sets the taskBounds to the passed in bounds,
     * startBounds
     */
    fun moveToFreeform(taskInfo: RunningTaskInfo, startBounds: Rect) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToFreeform with bounds taskId=%d",
            taskInfo.taskId
        )
        val wct = WindowContainerTransaction()
        moveHomeTaskToFront(wct)
        addMoveToDesktopChanges(wct, taskInfo)
        wct.setBounds(taskInfo.token, startBounds)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startTransition(
                    Transitions.TRANSIT_ENTER_FREEFORM, wct, mOnAnimationFinishedCallback)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Brings apps to front and sets freeform task bounds */
    private fun moveToDesktopWithAnimation(taskInfo: RunningTaskInfo, freeformBounds: Rect) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToDesktop with animation taskId=%d",
            taskInfo.taskId
        )
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(taskInfo.displayId, wct)
        addMoveToDesktopChanges(wct, taskInfo)
        wct.setBounds(taskInfo.token, freeformBounds)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startTransition(
                Transitions.TRANSIT_ENTER_DESKTOP_MODE, wct, mOnAnimationFinishedCallback)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
            releaseVisualIndicator()
        }
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveToFullscreen(task) }
    }

    /** Move a task to fullscreen */
    fun moveToFullscreen(task: RunningTaskInfo) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToFullscreen taskId=%d",
            task.taskId
        )

        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Move a task to fullscreen after being dragged from fullscreen and released back into
     * status bar area
     */
    fun cancelMoveToFreeform(task: RunningTaskInfo, position: Point) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: cancelMoveToFreeform taskId=%d",
            task.taskId
        )
        val wct = WindowContainerTransaction()
        wct.setBounds(task.token, null)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startCancelMoveToDesktopMode(
                wct, position) { t ->
                val callbackWCT = WindowContainerTransaction()
                visualIndicator?.releaseVisualIndicator(t)
                visualIndicator = null
                addMoveToFullscreenChanges(callbackWCT, task)
                shellTaskOrganizer.applyTransaction(callbackWCT)
            }
        } else {
            addMoveToFullscreenChanges(wct, task)
            shellTaskOrganizer.applyTransaction(wct)
            releaseVisualIndicator()
        }
    }

    private fun moveToFullscreenWithAnimation(task: RunningTaskInfo, position: Point) {
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: moveToFullscreen with animation taskId=%d",
                task.taskId
        )
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            exitDesktopTaskTransitionHandler.startTransition(
            Transitions.TRANSIT_EXIT_DESKTOP_MODE, wct, position, mOnAnimationFinishedCallback)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
            releaseVisualIndicator()
        }
    }

    /** Move a task to the front */
    fun moveTaskToFront(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveTaskToFront(task) }
    }

    /** Move a task to the front */
    fun moveTaskToFront(taskInfo: RunningTaskInfo) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveTaskToFront taskId=%d",
            taskInfo.taskId
        )

        val wct = WindowContainerTransaction()
        wct.reorder(taskInfo.token, true)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_TO_FRONT, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Move task to the next display.
     *
     * Queries all current known display ids and sorts them in ascending order. Then iterates
     * through the list and looks for the display id that is larger than the display id for
     * the passed in task. If a display with a higher id is not found, iterates through the list and
     * finds the first display id that is not the display id for the passed in task.
     *
     * If a display matching the above criteria is found, re-parents the task to that display.
     * No-op if no such display is found.
     */
    fun moveToNextDisplay(taskId: Int) {
        val task = shellTaskOrganizer.getRunningTaskInfo(taskId)
        if (task == null) {
            KtProtoLog.w(WM_SHELL_DESKTOP_MODE, "moveToNextDisplay: taskId=%d not found", taskId)
            return
        }
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToNextDisplay: taskId=%d taskDisplayId=%d",
                taskId, task.displayId)

        val displayIds = rootTaskDisplayAreaOrganizer.displayIds.sorted()
        // Get the first display id that is higher than current task display id
        var newDisplayId = displayIds.firstOrNull { displayId -> displayId > task.displayId }
        if (newDisplayId == null) {
            // No display with a higher id, get the first display id that is not the task display id
            newDisplayId = displayIds.firstOrNull { displayId -> displayId < task.displayId }
        }
        if (newDisplayId == null) {
            KtProtoLog.w(WM_SHELL_DESKTOP_MODE, "moveToNextDisplay: next display not found")
            return
        }
        moveToDisplay(task, newDisplayId)
    }

    /**
     * Move [task] to display with [displayId].
     *
     * No-op if task is already on that display per [RunningTaskInfo.displayId].
     */
    private fun moveToDisplay(task: RunningTaskInfo, displayId: Int) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToDisplay: taskId=%d displayId=%d",
                task.taskId, displayId)

        if (task.displayId == displayId) {
            KtProtoLog.d(WM_SHELL_DESKTOP_MODE, "moveToDisplay: task already on display")
            return
        }

        val displayAreaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)
        if (displayAreaInfo == null) {
            KtProtoLog.w(WM_SHELL_DESKTOP_MODE, "moveToDisplay: display not found")
            return
        }

        val wct = WindowContainerTransaction()
        wct.reparent(task.token, displayAreaInfo.token, true /* onTop */)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Quick-resizes a desktop task, toggling between the stable bounds and the default bounds. */
    fun toggleDesktopTaskSize(taskInfo: RunningTaskInfo, windowDecor: DesktopModeWindowDecoration) {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return

        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)
        val destinationBounds = Rect()
        if (taskInfo.configuration.windowConfiguration.bounds == stableBounds) {
            // The desktop task is currently occupying the whole stable bounds, toggle to the
            // default bounds.
            getDefaultDesktopTaskBounds(
                density = taskInfo.configuration.densityDpi.toFloat() / DENSITY_DEFAULT,
                stableBounds = stableBounds,
                outBounds = destinationBounds
            )
        } else {
            // Toggle to the stable bounds.
            destinationBounds.set(stableBounds)
        }

        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            toggleResizeDesktopTaskTransitionHandler.startTransition(
                wct,
                taskInfo.taskId,
                windowDecor
            )
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    private fun getDefaultDesktopTaskBounds(density: Float, stableBounds: Rect, outBounds: Rect) {
        val width = (DESKTOP_MODE_DEFAULT_WIDTH_DP * density + 0.5f).toInt()
        val height = (DESKTOP_MODE_DEFAULT_HEIGHT_DP * density + 0.5f).toInt()
        outBounds.set(0, 0, width, height)
        // Center the task in stable bounds
        outBounds.offset(
            stableBounds.centerX() - outBounds.centerX(),
            stableBounds.centerY() - outBounds.centerY()
        )
    }

    /**
     * Get windowing move for a given `taskId`
     *
     * @return [WindowingMode] for the task or [WINDOWING_MODE_UNDEFINED] if task is not found
     */
    @WindowingMode
    fun getTaskWindowingMode(taskId: Int): Int {
        return shellTaskOrganizer.getRunningTaskInfo(taskId)?.windowingMode
            ?: WINDOWING_MODE_UNDEFINED
    }

    private fun bringDesktopAppsToFront(displayId: Int, wct: WindowContainerTransaction) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: bringDesktopAppsToFront")
        val activeTasks = desktopModeTaskRepository.getActiveTasks(displayId)

        // First move home to front and then other tasks on top of it
        moveHomeTaskToFront(wct)

        val allTasksInZOrder = desktopModeTaskRepository.getFreeformTasksInZOrder()
        activeTasks
            // Sort descending as the top task is at index 0. It should be ordered to top last
            .sortedByDescending { taskId -> allTasksInZOrder.indexOf(taskId) }
            .mapNotNull { taskId -> shellTaskOrganizer.getRunningTaskInfo(taskId) }
            .forEach { task -> wct.reorder(task.token, true /* onTop */) }
    }

    private fun moveHomeTaskToFront(wct: WindowContainerTransaction) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), true /* onTop */) }
    }

    private fun releaseVisualIndicator() {
        val t = SurfaceControl.Transaction()
        visualIndicator?.releaseVisualIndicator(t)
        visualIndicator = null
        syncQueue.runInSync { transaction ->
            transaction.merge(t)
            t.close()
        }
    }

    override fun getContext(): Context {
        return context
    }

    override fun getRemoteCallExecutor(): ShellExecutor {
        return mainExecutor
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ): Boolean {
        // This handler should never be the sole handler, so should not animate anything.
        return false
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: handleRequest request=%s",
            request
        )
        // Check if we should skip handling this transition
        var reason = ""
        val shouldHandleRequest =
            when {
                // Only handle open or to front transitions
                request.type != TRANSIT_OPEN && request.type != TRANSIT_TO_FRONT -> {
                    reason = "transition type not handled (${request.type})"
                    false
                }
                // Only handle when it is a task transition
                request.triggerTask == null -> {
                    reason = "triggerTask is null"
                    false
                }
                // Only handle standard type tasks
                request.triggerTask.activityType != ACTIVITY_TYPE_STANDARD -> {
                    reason = "activityType not handled (${request.triggerTask.activityType})"
                    false
                }
                // Only handle fullscreen or freeform tasks
                request.triggerTask.windowingMode != WINDOWING_MODE_FULLSCREEN &&
                        request.triggerTask.windowingMode != WINDOWING_MODE_FREEFORM -> {
                    reason = "windowingMode not handled (${request.triggerTask.windowingMode})"
                    false
                }
                // Otherwise process it
                else -> true
            }

        if (!shouldHandleRequest) {
            KtProtoLog.v(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController: skipping handleRequest reason=%s",
                    reason
            )
            return null
        }

        val task: RunningTaskInfo = request.triggerTask

        val result = when {
            // If display has tasks stashed, handle as stashed launch
            desktopModeTaskRepository.isStashed(task.displayId) -> handleStashedTaskLaunch(task)
            // Check if fullscreen task should be updated
            task.windowingMode == WINDOWING_MODE_FULLSCREEN -> handleFullscreenTaskLaunch(task)
            // Check if freeform task should be updated
            task.windowingMode == WINDOWING_MODE_FREEFORM -> handleFreeformTaskLaunch(task)
            else -> {
                null
            }
        }
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: handleRequest result=%s",
                result ?: "null"
        )
        return result
    }

    /**
     * Applies the proper surface states (rounded corners) to tasks when desktop mode is active.
     * This is intended to be used when desktop mode is part of another animation but isn't, itself,
     * animating.
     */
    fun syncSurfaceState(
            info: TransitionInfo,
            finishTransaction: SurfaceControl.Transaction
    ) {
        // Add rounded corners to freeform windows
        val ta: TypedArray = context.obtainStyledAttributes(
                intArrayOf(R.attr.dialogCornerRadius))
        val cornerRadius = ta.getDimensionPixelSize(0, 0).toFloat()
        ta.recycle()
        info.changes
                .filter { it.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM }
                .forEach { finishTransaction.setCornerRadius(it.leash, cornerRadius) }
    }

    private fun handleFreeformTaskLaunch(task: RunningTaskInfo): WindowContainerTransaction? {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: handleFreeformTaskLaunch")
        val activeTasks = desktopModeTaskRepository.getActiveTasks(task.displayId)
        if (activeTasks.none { desktopModeTaskRepository.isVisibleTask(it) }) {
            KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController: switch freeform task to fullscreen oon transition" +
                            " taskId=%d",
                    task.taskId
            )
            return WindowContainerTransaction().also { wct ->
                addMoveToFullscreenChanges(wct, task)
            }
        }
        return null
    }

    private fun handleFullscreenTaskLaunch(task: RunningTaskInfo): WindowContainerTransaction? {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: handleFullscreenTaskLaunch")
        val activeTasks = desktopModeTaskRepository.getActiveTasks(task.displayId)
        if (activeTasks.any { desktopModeTaskRepository.isVisibleTask(it) }) {
            KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController: switch fullscreen task to freeform on transition" +
                            " taskId=%d",
                    task.taskId
            )
            return WindowContainerTransaction().also { wct ->
                addMoveToDesktopChanges(wct, task)
            }
        }
        return null
    }

    private fun handleStashedTaskLaunch(task: RunningTaskInfo): WindowContainerTransaction {
        KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: launch apps with stashed on transition taskId=%d",
                task.taskId
        )
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(task.displayId, wct)
        addMoveToDesktopChanges(wct, task)
        desktopModeTaskRepository.setStashed(task.displayId, false)
        return wct
    }

    private fun addMoveToDesktopChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ) {
        val displayWindowingMode = taskInfo.configuration.windowConfiguration.displayWindowingMode
        val targetWindowingMode = if (displayWindowingMode == WINDOWING_MODE_FREEFORM) {
            // Display windowing is freeform, set to undefined and inherit it
            WINDOWING_MODE_UNDEFINED
        } else {
            WINDOWING_MODE_FREEFORM
        }
        wct.setWindowingMode(taskInfo.token, targetWindowingMode)
        wct.reorder(taskInfo.token, true /* onTop */)
        if (isDesktopDensityOverrideSet()) {
            wct.setDensityDpi(taskInfo.token, getDesktopDensityDpi())
        }
    }

    private fun addMoveToFullscreenChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ) {
        val displayWindowingMode = taskInfo.configuration.windowConfiguration.displayWindowingMode
        val targetWindowingMode = if (displayWindowingMode == WINDOWING_MODE_FULLSCREEN) {
            // Display windowing is fullscreen, set to undefined and inherit it
            WINDOWING_MODE_UNDEFINED
        } else {
            WINDOWING_MODE_FULLSCREEN
        }
        wct.setWindowingMode(taskInfo.token, targetWindowingMode)
        wct.setBounds(taskInfo.token, null)
        if (isDesktopDensityOverrideSet()) {
            wct.setDensityDpi(taskInfo.token, getFullscreenDensityDpi())
        }
    }

    private fun getFullscreenDensityDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    private fun getDesktopDensityDpi(): Int {
        return DESKTOP_DENSITY_OVERRIDE
    }

    /** Creates a new instance of the external interface to pass to another process. */
    private fun createExternalInterface(): ExternalInterfaceBinder {
        return IDesktopModeImpl(this)
    }

    /** Get connection interface between sysui and shell */
    fun asDesktopMode(): DesktopMode {
        return desktopMode
    }

    /**
     * Perform checks required on drag move. Create/release fullscreen indicator as needed.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface SurfaceControl of dragged task.
     * @param y coordinate of dragged task. Used for checks against status bar height.
     */
    fun onDragPositioningMove(
            taskInfo: RunningTaskInfo,
            taskSurface: SurfaceControl,
            y: Float
    ) {
        if (taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
            if (y <= transitionAreaHeight && visualIndicator == null) {
                visualIndicator = DesktopModeVisualIndicator(syncQueue, taskInfo,
                        displayController, context, taskSurface, shellTaskOrganizer,
                        rootTaskDisplayAreaOrganizer)
                visualIndicator?.createFullscreenIndicatorWithAnimatedBounds()
            } else if (y > transitionAreaHeight && visualIndicator != null) {
                releaseVisualIndicator()
            }
        }
    }

    /**
     * Perform checks required on drag end. Move to fullscreen if drag ends in status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param position position of surface when drag ends.
     * @param y the Y position of the motion event.
     * @param windowDecor the window decoration for the task being dragged
     */
    fun onDragPositioningEnd(
            taskInfo: RunningTaskInfo,
            position: Point,
            y: Float,
            windowDecor: DesktopModeWindowDecoration
    ) {
        if (y <= transitionAreaHeight && taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
            windowDecor.incrementRelayoutBlock()
            moveToFullscreenWithAnimation(taskInfo, position)
        }
    }

    /**
     * Perform checks required on drag move. Create/release fullscreen indicator and transitions
     * indicator to freeform or fullscreen dimensions as needed.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface SurfaceControl of dragged task.
     * @param y coordinate of dragged task. Used for checks against status bar height.
     */
    fun onDragPositioningMoveThroughStatusBar(
            taskInfo: RunningTaskInfo,
            taskSurface: SurfaceControl,
            y: Float
    ) {
        // If the motion event is above the status bar and the visual indicator is not yet visible,
        // return since we do not need to show the visual indicator at this point.
        if (y < getStatusBarHeight(taskInfo) && visualIndicator == null) {
            return
        }
        if (visualIndicator == null) {
            visualIndicator = DesktopModeVisualIndicator(syncQueue, taskInfo,
                    displayController, context, taskSurface, shellTaskOrganizer,
                    rootTaskDisplayAreaOrganizer)
            visualIndicator?.createFullscreenIndicator()
        }
        val indicator = visualIndicator ?: return
        if (y >= getFreeformTransitionStatusBarDragThreshold(taskInfo)) {
            if (indicator.isFullscreen) {
                indicator.transitionFullscreenIndicatorToFreeform()
            }
        } else if (!indicator.isFullscreen) {
            indicator.transitionFreeformIndicatorToFullscreen()
        }
    }

    /**
     * Perform checks required when drag ends under status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param y height of drag, to be checked against status bar height.
     */
    fun onDragPositioningEndThroughStatusBar(
            taskInfo: RunningTaskInfo,
            freeformBounds: Rect
    ) {
        moveToDesktopWithAnimation(taskInfo, freeformBounds)
    }

    private fun getStatusBarHeight(taskInfo: RunningTaskInfo): Int {
        return displayController.getDisplayLayout(taskInfo.displayId)?.stableInsets()?.top ?: 0
    }

    /**
     * Returns the threshold at which we transition a task into freeform when dragging a
     * fullscreen task down from the status bar
     */
    private fun getFreeformTransitionStatusBarDragThreshold(taskInfo: RunningTaskInfo): Int {
        return 2 * getStatusBarHeight(taskInfo)
    }

    /**
     * Update the corner region for a specified task
     */
    fun onTaskCornersChanged(taskId: Int, corner: Region) {
        desktopModeTaskRepository.updateTaskCorners(taskId, corner)
    }

    /**
     * Remove a previously tracked corner region for a specified task.
     */
    fun removeCornersForTask(taskId: Int) {
        desktopModeTaskRepository.removeTaskCorners(taskId)
    }

    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun addVisibleTasksListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
        desktopModeTaskRepository.addVisibleTasksListener(listener, callbackExecutor)
    }

    /**
     * Adds a listener to track changes to desktop task corners
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun setTaskCornerListener(
            listener: Consumer<Region>,
            callbackExecutor: Executor
    ) {
        desktopModeTaskRepository.setTaskCornerListener(listener, callbackExecutor)
    }

    private fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopTasksController")
        desktopModeTaskRepository.dump(pw, innerPrefix)
    }

    /** The interface for calls from outside the shell, within the host process. */
    @ExternalThread
    private inner class DesktopModeImpl : DesktopMode {
        override fun addVisibleTasksListener(
                listener: VisibleTasksListener,
                callbackExecutor: Executor
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.addVisibleTasksListener(listener, callbackExecutor)
            }
        }

        override fun addDesktopGestureExclusionRegionListener(
                listener: Consumer<Region>,
                callbackExecutor: Executor
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.setTaskCornerListener(listener, callbackExecutor)
            }
        }
    }

    /** The interface for calls from outside the host process. */
    @BinderThread
    private class IDesktopModeImpl(private var controller: DesktopTasksController?) :
        IDesktopMode.Stub(), ExternalInterfaceBinder {

        private lateinit var remoteListener:
                SingleInstanceRemoteListener<DesktopTasksController, IDesktopTaskListener>

        private val listener: VisibleTasksListener = object : VisibleTasksListener {
            override fun onVisibilityChanged(displayId: Int, visible: Boolean) {
                KtProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onVisibilityChanged display=%d visible=%b",
                        displayId,
                        visible
                )
                remoteListener.call { l -> l.onVisibilityChanged(displayId, visible) }
            }

            override fun onStashedChanged(displayId: Int, stashed: Boolean) {
                KtProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onStashedChanged display=%d stashed=%b",
                        displayId,
                        stashed
                )
                remoteListener.call { l -> l.onStashedChanged(displayId, stashed) }
            }
        }

        init {
            remoteListener =
                    SingleInstanceRemoteListener<DesktopTasksController, IDesktopTaskListener>(
                            controller,
                            { c ->
                                c.desktopModeTaskRepository.addVisibleTasksListener(
                                        listener,
                                        c.mainExecutor
                                )
                            },
                            { c ->
                                c.desktopModeTaskRepository.removeVisibleTasksListener(listener)
                            }
                    )
        }

        /** Invalidates this instance, preventing future calls from updating the controller. */
        override fun invalidate() {
            remoteListener.unregister()
            controller = null
        }

        override fun showDesktopApps(displayId: Int) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "showDesktopApps"
            ) { c -> c.showDesktopApps(displayId) }
        }

        override fun stashDesktopApps(displayId: Int) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                    controller,
                    "stashDesktopApps"
            ) { c -> c.stashDesktopApps(displayId) }
        }

        override fun hideStashedDesktopApps(displayId: Int) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                    controller,
                    "hideStashedDesktopApps"
            ) { c -> c.hideStashedDesktopApps(displayId) }
        }

        override fun showDesktopApp(taskId: Int) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                    controller,
                    "showDesktopApp"
            ) { c -> c.moveTaskToFront(taskId) }
        }

        override fun getVisibleTaskCount(displayId: Int): Int {
            val result = IntArray(1)
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "getVisibleTaskCount",
                { controller -> result[0] = controller.getVisibleTaskCount(displayId) },
                true /* blocking */
            )
            return result[0]
        }

        override fun setTaskListener(listener: IDesktopTaskListener?) {
            KtProtoLog.v(
                    WM_SHELL_DESKTOP_MODE,
                    "IDesktopModeImpl: set task listener=%s",
                    listener ?: "null"
            )
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                    controller,
                    "setTaskListener"
            ) { _ -> listener?.let { remoteListener.register(it) } ?: remoteListener.unregister() }
        }
    }

    companion object {
        private val DESKTOP_DENSITY_OVERRIDE =
            SystemProperties.getInt("persist.wm.debug.desktop_mode_density", 284)
        private val DESKTOP_DENSITY_ALLOWED_RANGE = (100..1000)

        // Override default freeform task width when desktop mode is enabled. In dips.
        private val DESKTOP_MODE_DEFAULT_WIDTH_DP =
            SystemProperties.getInt("persist.wm.debug.desktop_mode.default_width", 840)

        // Override default freeform task height when desktop mode is enabled. In dips.
        private val DESKTOP_MODE_DEFAULT_HEIGHT_DP =
            SystemProperties.getInt("persist.wm.debug.desktop_mode.default_height", 630)

        /**
         * Check if desktop density override is enabled
         */
        @JvmStatic
        fun isDesktopDensityOverrideSet(): Boolean {
            return DESKTOP_DENSITY_OVERRIDE in DESKTOP_DENSITY_ALLOWED_RANGE
        }
    }
}
