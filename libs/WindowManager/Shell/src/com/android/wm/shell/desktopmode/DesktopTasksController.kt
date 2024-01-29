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

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.IBinder
import android.os.SystemProperties
import android.util.DisplayMetrics.DENSITY_DEFAULT
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.RemoteTransition
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.internal.policy.ScreenDecorationsUtils
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
import com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler.DragToDesktopStateListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_ENTER_DESKTOP
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.OneShotRemoteHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.util.KtProtoLog
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator
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
        private val dragToDesktopTransitionHandler: DragToDesktopTransitionHandler,
        private val desktopModeTaskRepository: DesktopModeTaskRepository,
        private val launchAdjacentController: LaunchAdjacentController,
        private val recentsTransitionHandler: RecentsTransitionHandler,
        @ShellMainThread private val mainExecutor: ShellExecutor
) : RemoteCallable<DesktopTasksController>, Transitions.TransitionHandler {

    private val desktopMode: DesktopModeImpl
    private var visualIndicator: DesktopModeVisualIndicator? = null
    private val desktopModeShellCommandHandler: DesktopModeShellCommandHandler =
        DesktopModeShellCommandHandler(this)

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
    private val dragToDesktopStateListener = object : DragToDesktopStateListener {
        override fun onCommitToDesktopAnimationStart(tx: SurfaceControl.Transaction) {
            removeVisualIndicator(tx)
        }

        override fun onCancelToDesktopAnimationEnd(tx: SurfaceControl.Transaction) {
            removeVisualIndicator(tx)
        }

        private fun removeVisualIndicator(tx: SurfaceControl.Transaction) {
            visualIndicator?.releaseVisualIndicator(tx)
            visualIndicator = null
        }
    }

    private val transitionAreaHeight
        get() = context.resources.getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_transition_area_height
        )

    private val transitionAreaWidth
        get() = context.resources.getDimensionPixelSize(
            com.android.wm.shell.R.dimen.desktop_mode_transition_area_width
        )

    private var recentsAnimationRunning = false
    private lateinit var splitScreenController: SplitScreenController

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.isEnabled()) {
            shellInit.addInitCallback({ onInit() }, this)
        }
    }

    private fun onInit() {
        KtProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopTasksController")
        shellCommandHandler.addDumpCallback(this::dump, this)
        shellCommandHandler.addCommandCallback("desktopmode", desktopModeShellCommandHandler,
            this)
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
        transitions.addHandler(this)
        desktopModeTaskRepository.addVisibleTasksListener(taskVisibilityListener, mainExecutor)
        dragToDesktopTransitionHandler.setDragToDesktopStateListener(dragToDesktopStateListener)
        recentsTransitionHandler.addTransitionStateListener(
            object : RecentsTransitionStateListener {
                override fun onAnimationStateChanged(running: Boolean) {
                    KtProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "DesktopTasksController: recents animation state changed running=%b",
                        running
                    )
                    recentsAnimationRunning = running
                }
            }
        )
    }

    /** Setter needed to avoid cyclic dependency. */
    fun setSplitScreenController(controller: SplitScreenController) {
        splitScreenController = controller
        dragToDesktopTransitionHandler.setSplitScreenController(controller)
    }

    /** Show all tasks, that are part of the desktop, on top of launcher */
    fun showDesktopApps(displayId: Int, remoteTransition: RemoteTransition? = null) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: showDesktopApps")
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(displayId, wct)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            // TODO(b/255649902): ensure remote transition is supplied once state is introduced
            val transitionType = if (remoteTransition == null) TRANSIT_NONE else TRANSIT_TO_FRONT
            val handler = remoteTransition?.let {
                OneShotRemoteHandler(transitions.mainExecutor, remoteTransition)
            }
            transitions.startTransition(transitionType, wct, handler).also { t ->
                handler?.setTransition(t)
            }
        } else {
            shellTaskOrganizer.applyTransaction(wct)
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
        if (DesktopModeStatus.isStashingEnabled()) {
            KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: stashDesktopApps")
            desktopModeTaskRepository.setStashed(displayId, true)
        }
    }

    /**
     * Clear the stashed state for the given display
     */
    fun hideStashedDesktopApps(displayId: Int) {
        if (DesktopModeStatus.isStashingEnabled()) {
            KtProtoLog.v(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController: hideStashedApps displayId=%d",
                    displayId
            )
            desktopModeTaskRepository.setStashed(displayId, false)
        }
    }

    /** Get number of tasks that are marked as visible */
    fun getVisibleTaskCount(displayId: Int): Int {
        return desktopModeTaskRepository.getVisibleTaskCount(displayId)
    }

    /** Move a task with given `taskId` to desktop */
    fun moveToDesktop(
            decor: DesktopModeWindowDecoration,
            taskId: Int,
            wct: WindowContainerTransaction = WindowContainerTransaction()
    ) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let {
            task -> moveToDesktop(decor, task, wct)
        }
    }

    /** Move a task with given `taskId` to desktop without decor */
    fun moveToDesktopWithoutDecor(
        taskId: Int,
        wct: WindowContainerTransaction
    ): Boolean {
        val task = shellTaskOrganizer.getRunningTaskInfo(taskId) ?: return false
        moveToDesktopWithoutDecor(task, wct)
        return true
    }

    /**
     * Move a task to desktop without decor
     */
    private fun moveToDesktopWithoutDecor(
        task: RunningTaskInfo,
        wct: WindowContainerTransaction
    ) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToDesktopWithoutDecor taskId=%d",
            task.taskId
        )
        exitSplitIfApplicable(wct, task)
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
     * Move a task to desktop
     */
    fun moveToDesktop(
            decor: DesktopModeWindowDecoration,
            task: RunningTaskInfo,
            wct: WindowContainerTransaction = WindowContainerTransaction()
    ) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToDesktop taskId=%d",
            task.taskId
        )
        exitSplitIfApplicable(wct, task)
        // Bring other apps to front first
        bringDesktopAppsToFront(task.displayId, wct)
        addMoveToDesktopChanges(wct, task)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.moveToDesktop(wct, decor)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * The first part of the animated drag to desktop transition. This is
     * followed with a call to [finalizeDragToDesktop] or [cancelDragToDesktop].
     */
    fun startDragToDesktop(
            taskInfo: RunningTaskInfo,
            dragToDesktopValueAnimator: MoveToDesktopAnimator,
            windowDecor: DesktopModeWindowDecoration
    ) {
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: startDragToDesktop taskId=%d",
                taskInfo.taskId
        )
        dragToDesktopTransitionHandler.startDragToDesktopTransition(
                taskInfo.taskId,
                dragToDesktopValueAnimator,
                windowDecor
        )
    }

    /**
     * The second part of the animated drag to desktop transition, called after
     * [startDragToDesktop].
     */
    private fun finalizeDragToDesktop(taskInfo: RunningTaskInfo, freeformBounds: Rect) {
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: finalizeDragToDesktop taskId=%d",
                taskInfo.taskId
        )
        val wct = WindowContainerTransaction()
        exitSplitIfApplicable(wct, taskInfo)
        moveHomeTaskToFront(wct)
        bringDesktopAppsToFront(taskInfo.displayId, wct)
        addMoveToDesktopChanges(wct, taskInfo)
        wct.setBounds(taskInfo.token, freeformBounds)
        dragToDesktopTransitionHandler.finishDragToDesktopTransition(wct)
    }

    /**
     * Perform needed cleanup transaction once animation is complete. Bounds need to be set
     * here instead of initial wct to both avoid flicker and to have task bounds to use for
     * the staging animation.
     *
     * @param taskInfo task entering split that requires a bounds update
     */
    fun onDesktopSplitSelectAnimComplete(taskInfo: RunningTaskInfo) {
        val wct = WindowContainerTransaction()
        wct.setBounds(taskInfo.token, Rect())
        shellTaskOrganizer.applyTransaction(wct)
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task ->
            moveToFullscreenWithAnimation(task, task.positionInParent)
        }
    }

    /** Move a desktop app to split screen. */
    fun moveToSplit(task: RunningTaskInfo) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToSplit taskId=%d",
            task.taskId
        )
        val wct = WindowContainerTransaction()
        wct.setBounds(task.token, Rect())
        addMoveToSplitChanges(wct, task)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    private fun exitSplitIfApplicable(wct: WindowContainerTransaction, taskInfo: RunningTaskInfo) {
        if (splitScreenController.isTaskInSplitScreen(taskInfo.taskId)) {
            splitScreenController.prepareExitSplitScreen(
                    wct,
                    splitScreenController.getStageOfTask(taskInfo.taskId),
                    EXIT_REASON_ENTER_DESKTOP
            )
            getOtherSplitTask(taskInfo.taskId)?.let { otherTaskInfo ->
                wct.removeTask(otherTaskInfo.token)
            }
        }
    }

    private fun getOtherSplitTask(taskId: Int): RunningTaskInfo? {
        val remainingTaskPosition: Int =
                if (splitScreenController.getSplitPosition(taskId)
                        == SPLIT_POSITION_BOTTOM_OR_RIGHT) {
                    SPLIT_POSITION_TOP_OR_LEFT
                } else {
                    SPLIT_POSITION_BOTTOM_OR_RIGHT
                }
        return splitScreenController.getTaskInfo(remainingTaskPosition)
    }

    /**
     * The second part of the animated drag to desktop transition, called after
     * [startDragToDesktop].
     */
    fun cancelDragToDesktop(task: RunningTaskInfo) {
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: cancelDragToDesktop taskId=%d",
            task.taskId
        )
        dragToDesktopTransitionHandler.cancelDragToDesktopTransition()
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

    /**
     * Quick-resize to the right or left half of the stable bounds.
     *
     * @param position the portion of the screen (RIGHT or LEFT) we want to snap the task to.
     */
    fun snapToHalfScreen(
            taskInfo: RunningTaskInfo,
            windowDecor: DesktopModeWindowDecoration,
            position: SnapPosition
    ) {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return

        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)

        val destinationWidth = stableBounds.width() / 2
        val destinationBounds = when (position) {
            SnapPosition.LEFT -> {
                Rect(
                        stableBounds.left,
                        stableBounds.top,
                        stableBounds.left + destinationWidth,
                        stableBounds.bottom
                )
            }
            SnapPosition.RIGHT -> {
                Rect(
                        stableBounds.right - destinationWidth,
                        stableBounds.top,
                        stableBounds.right,
                        stableBounds.bottom
                )
            }
        }

        if (destinationBounds == taskInfo.configuration.windowConfiguration.bounds) return

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
        val triggerTask = request.triggerTask
        val shouldHandleRequest =
            when {
                recentsAnimationRunning -> {
                    reason = "recents animation is running"
                    false
                }
                // Only handle open or to front transitions
                request.type != TRANSIT_OPEN && request.type != TRANSIT_TO_FRONT -> {
                    reason = "transition type not handled (${request.type})"
                    false
                }
                // Only handle when it is a task transition
                triggerTask == null -> {
                    reason = "triggerTask is null"
                    false
                }
                // Only handle standard type tasks
                triggerTask.activityType != ACTIVITY_TYPE_STANDARD -> {
                    reason = "activityType not handled (${triggerTask.activityType})"
                    false
                }
                // Only handle fullscreen or freeform tasks
                triggerTask.windowingMode != WINDOWING_MODE_FULLSCREEN &&
                        triggerTask.windowingMode != WINDOWING_MODE_FREEFORM -> {
                    reason = "windowingMode not handled (${triggerTask.windowingMode})"
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

        val result = triggerTask?.let { task ->
            when {
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
        if (!DesktopModeStatus.useRoundedCorners()) {
            return
        }
        val cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)
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
        wct.setBounds(taskInfo.token, Rect())
        if (isDesktopDensityOverrideSet()) {
            wct.setDensityDpi(taskInfo.token, getDefaultDensityDpi())
        }
    }

    /**
     * Adds split screen changes to a transaction. Note that bounds are not reset here due to
     * animation; see {@link onDesktopSplitSelectAnimComplete}
     */
    private fun addMoveToSplitChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ) {
        // Explicitly setting multi-window at task level interferes with animations.
        // Let task inherit windowing mode once transition is complete instead.
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED)
        // The task's density may have been overridden in freeform; revert it here as we don't
        // want it overridden in multi-window.
        wct.setDensityDpi(taskInfo.token, getDefaultDensityDpi())
    }

    /**
     * Requests a task be transitioned from desktop to split select. Applies needed windowing
     * changes if this transition is enabled.
     */
    fun requestSplit(
        taskInfo: RunningTaskInfo
    ) {
        val windowingMode = taskInfo.windowingMode
        if (windowingMode == WINDOWING_MODE_FULLSCREEN || windowingMode == WINDOWING_MODE_FREEFORM
        ) {
            val wct = WindowContainerTransaction()
            addMoveToSplitChanges(wct, taskInfo)
            splitScreenController.requestEnterSplitSelect(taskInfo, wct,
                SPLIT_POSITION_BOTTOM_OR_RIGHT, taskInfo.configuration.windowConfiguration.bounds)
        }
    }

    private fun getDefaultDensityDpi(): Int {
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
     * Different sources for x and y coordinates are used due to different needs for each:
     * We want split transitions to be based on input coordinates but fullscreen transition
     * to be based on task edge coordinate.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface SurfaceControl of dragged task.
     * @param inputX x coordinate of input. Used for checks against left/right edge of screen.
     * @param taskBounds bounds of dragged task. Used for checks against status bar height.
     */
    fun onDragPositioningMove(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
        inputX: Float,
        taskBounds: Rect
    ) {
        if (taskInfo.windowingMode != WINDOWING_MODE_FREEFORM) return
        updateVisualIndicator(taskInfo, taskSurface, inputX, taskBounds.top.toFloat())
    }

    fun updateVisualIndicator(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
        inputX: Float,
        taskTop: Float
    ) {
        // If the visual indicator does not exist, create it.
        if (visualIndicator == null) {
            visualIndicator = DesktopModeVisualIndicator(
                syncQueue, taskInfo, displayController, context, taskSurface,
                rootTaskDisplayAreaOrganizer)
        }
        // Then, update the indicator type.
        val indicator = visualIndicator ?: return
        indicator.updateIndicatorType(PointF(inputX, taskTop))
    }

    /**
     * Perform checks required on drag end. Move to fullscreen if drag ends in status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param position position of surface when drag ends.
     * @param inputCoordinate the coordinates of the motion event
     * @param taskBounds the updated bounds of the task being dragged.
     */
    fun onDragPositioningEnd(
        taskInfo: RunningTaskInfo,
        position: Point,
        inputCoordinate: PointF,
        taskBounds: Rect
    ) {
        if (taskInfo.configuration.windowConfiguration.windowingMode != WINDOWING_MODE_FREEFORM) {
            return
        }
        if (taskBounds.top <= transitionAreaHeight) {
            moveToFullscreenWithAnimation(taskInfo, position)
        }
        if (inputCoordinate.x <= transitionAreaWidth) {
            releaseVisualIndicator()
            var wct = WindowContainerTransaction()
            addMoveToSplitChanges(wct, taskInfo)
            splitScreenController.requestEnterSplitSelect(taskInfo, wct,
                SPLIT_POSITION_TOP_OR_LEFT, taskBounds)
        }
        if (inputCoordinate.x >= (displayController.getDisplayLayout(taskInfo.displayId)?.width()
            ?.minus(transitionAreaWidth) ?: return)) {
            releaseVisualIndicator()
            var wct = WindowContainerTransaction()
            addMoveToSplitChanges(wct, taskInfo)
            splitScreenController.requestEnterSplitSelect(taskInfo, wct,
                SPLIT_POSITION_BOTTOM_OR_RIGHT, taskBounds)
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
        finalizeDragToDesktop(taskInfo, freeformBounds)
    }

    private fun getStatusBarHeight(taskInfo: RunningTaskInfo): Int {
        return displayController.getDisplayLayout(taskInfo.displayId)?.stableInsets()?.top ?: 0
    }

    /**
     * Update the exclusion region for a specified task
     */
    fun onExclusionRegionChanged(taskId: Int, exclusionRegion: Region) {
        desktopModeTaskRepository.updateTaskExclusionRegions(taskId, exclusionRegion)
    }

    /**
     * Remove a previously tracked exclusion region for a specified task.
     */
    fun removeExclusionRegionForTask(taskId: Int) {
        desktopModeTaskRepository.removeExclusionRegion(taskId)
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
     * Adds a listener to track changes to desktop task gesture exclusion regions
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun setTaskRegionListener(
            listener: Consumer<Region>,
            callbackExecutor: Executor
    ) {
        desktopModeTaskRepository.setExclusionRegionListener(listener, callbackExecutor)
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
                this@DesktopTasksController.setTaskRegionListener(listener, callbackExecutor)
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

        override fun showDesktopApps(displayId: Int, remoteTransition: RemoteTransition?) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "showDesktopApps"
            ) { c -> c.showDesktopApps(displayId, remoteTransition) }
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

        override fun onDesktopSplitSelectAnimComplete(taskInfo: RunningTaskInfo) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "onDesktopSplitSelectAnimComplete"
            ) { c -> c.onDesktopSplitSelectAnimComplete(taskInfo) }
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

    /** The positions on a screen that a task can snap to. */
    enum class SnapPosition { RIGHT, LEFT }
}
