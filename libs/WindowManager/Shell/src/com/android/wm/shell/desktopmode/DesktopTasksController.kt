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
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.TaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.IBinder
import android.os.SystemProperties
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.RemoteTransition
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.window.flags.Flags
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.ExecutorUtils
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.LaunchAdjacentController
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.MultiInstanceHelper.Companion.getComponent
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SingleInstanceRemoteListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.compatui.isSingleTopActivityTranslucent
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler.DragToDesktopStateListener
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.shared.annotations.ExternalThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.OneShotRemoteHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.util.KtProtoLog
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener
import com.android.wm.shell.windowdecor.extension.isFreeform
import com.android.wm.shell.windowdecor.extension.isFullscreen
import java.io.PrintWriter
import java.util.Optional
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
        private val dragAndDropController: DragAndDropController,
        private val transitions: Transitions,
        private val enterDesktopTaskTransitionHandler: EnterDesktopTaskTransitionHandler,
        private val exitDesktopTaskTransitionHandler: ExitDesktopTaskTransitionHandler,
        private val toggleResizeDesktopTaskTransitionHandler:
        ToggleResizeDesktopTaskTransitionHandler,
        private val dragToDesktopTransitionHandler: DragToDesktopTransitionHandler,
        private val desktopModeTaskRepository: DesktopModeTaskRepository,
        private val desktopModeLoggerTransitionObserver: DesktopModeLoggerTransitionObserver,
        private val launchAdjacentController: LaunchAdjacentController,
        private val recentsTransitionHandler: RecentsTransitionHandler,
        private val multiInstanceHelper: MultiInstanceHelper,
        @ShellMainThread private val mainExecutor: ShellExecutor,
        private val desktopTasksLimiter: Optional<DesktopTasksLimiter>,
) : RemoteCallable<DesktopTasksController>, Transitions.TransitionHandler,
    DragAndDropController.DragAndDropListener {

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
        override fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {
            launchAdjacentController.launchAdjacentEnabled = visibleTasksCount == 0
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
                com.android.wm.shell.R.dimen.desktop_mode_fullscreen_from_desktop_height
        )

    private val transitionAreaWidth
        get() = context.resources.getDimensionPixelSize(
            com.android.wm.shell.R.dimen.desktop_mode_transition_area_width
        )

    /** Task id of the task currently being dragged from fullscreen/split. */
    val draggingTaskId
        get() = dragToDesktopTransitionHandler.draggingTaskId

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
        shellCommandHandler.addCommandCallback(
            "desktopmode",
            desktopModeShellCommandHandler,
            this
        )
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
        dragAndDropController.addListener(this)
    }

    fun setOnTaskResizeAnimationListener(listener: OnTaskResizeAnimationListener) {
        toggleResizeDesktopTaskTransitionHandler.setOnTaskResizeAnimationListener(listener)
        enterDesktopTaskTransitionHandler.setOnTaskResizeAnimationListener(listener)
        dragToDesktopTransitionHandler.setOnTaskResizeAnimatorListener(listener)
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

    /** Enter desktop by using the focused task in given `displayId` */
    fun moveFocusedTaskToDesktop(displayId: Int) {
        val allFocusedTasks =
            shellTaskOrganizer.getRunningTasks(displayId).filter { taskInfo ->
                taskInfo.isFocused &&
                        (taskInfo.windowingMode == WINDOWING_MODE_FULLSCREEN ||
                                taskInfo.windowingMode == WINDOWING_MODE_MULTI_WINDOW) &&
                        taskInfo.activityType != ACTIVITY_TYPE_HOME
            }
        if (allFocusedTasks.isNotEmpty()) {
            when (allFocusedTasks.size) {
                2 -> {
                    // Split-screen case where there are two focused tasks, then we find the child
                    // task to move to desktop.
                    val splitFocusedTask =
                        if (allFocusedTasks[0].taskId == allFocusedTasks[1].parentTaskId) {
                            allFocusedTasks[1]
                        } else {
                            allFocusedTasks[0]
                        }
                    moveToDesktop(splitFocusedTask)
                }
                1 -> {
                    // Fullscreen case where we move the current focused task.
                    moveToDesktop(allFocusedTasks[0].taskId)
                }
                else -> {
                    KtProtoLog.w(
                        WM_SHELL_DESKTOP_MODE,
                        "DesktopTasksController: Cannot enter desktop, expected less " +
                                "than 3 focused tasks but found %d",
                        allFocusedTasks.size
                    )
                }
            }
        }
    }

    /** Move a task with given `taskId` to desktop */
    fun moveToDesktop(
            taskId: Int,
            wct: WindowContainerTransaction = WindowContainerTransaction()
    ): Boolean {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let {
            task -> moveToDesktop(task, wct)
        } ?: return false
        return true
    }

    /**
     * Move a task to desktop
     */
    fun moveToDesktop(
            task: RunningTaskInfo,
            wct: WindowContainerTransaction = WindowContainerTransaction()
    ) {
        if (!DesktopModeStatus.canEnterDesktopMode(context)) {
            KtProtoLog.w(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: Cannot enter desktop, " +
                        "display does not meet minimum size requirements"
            )
            return
        }
        if (Flags.enableDesktopWindowingModalsPolicy() && isSingleTopActivityTranslucent(task)) {
            KtProtoLog.w(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: Cannot enter desktop, " +
                        "translucent top activity found. This is likely a modal dialog."
            )
            return
        }
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: moveToDesktop taskId=%d",
            task.taskId
        )
        exitSplitIfApplicable(wct, task)
        // Bring other apps to front first
        val taskToMinimize =
                bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
        addMoveToDesktopChanges(wct, task)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            val transition = enterDesktopTaskTransitionHandler.moveToDesktop(wct)
            addPendingMinimizeTransition(transition, taskToMinimize)
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
    ) {
        KtProtoLog.v(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: startDragToDesktop taskId=%d",
                taskInfo.taskId
        )
        dragToDesktopTransitionHandler.startDragToDesktopTransition(
                taskInfo.taskId,
                dragToDesktopValueAnimator
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
        val taskToMinimize =
                bringDesktopAppsToFrontBeforeShowingNewTask(
                        taskInfo.displayId, wct, taskInfo.taskId)
        addMoveToDesktopChanges(wct, taskInfo)
        wct.setBounds(taskInfo.token, freeformBounds)
        val transition = dragToDesktopTransitionHandler.finishDragToDesktopTransition(wct)
        transition?.let { addPendingMinimizeTransition(it, taskToMinimize) }
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
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED)
        shellTaskOrganizer.applyTransaction(wct)
    }

    /**
     * Perform clean up of the desktop wallpaper activity if the closed window task is
     * the last active task.
     *
     * @param wct transaction to modify if the last active task is closed
     * @param taskId task id of the window that's being closed
     */
    fun onDesktopWindowClose(
        wct: WindowContainerTransaction,
        taskId: Int
    ) {
        if (desktopModeTaskRepository.isOnlyActiveTask(taskId)) {
            removeWallpaperActivity(wct)
        }
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task ->
            moveToFullscreenWithAnimation(task, task.positionInParent)
        }
    }

    /** Enter fullscreen by moving the focused freeform task in given `displayId` to fullscreen. */
    fun enterFullscreen(displayId: Int) {
        getFocusedFreeformTask(displayId)
                ?.let { moveToFullscreenWithAnimation(it, it.positionInParent) }
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
        // Rather than set windowing mode to multi-window at task level, set it to
        // undefined and inherit from split stage.
        wct.setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)
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
                EXIT_REASON_DESKTOP_MODE
            )
            splitScreenController.transitionHandler
                ?.onSplitToDesktop()
        }
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
            Transitions.TRANSIT_EXIT_DESKTOP_MODE,
                wct,
                position,
                mOnAnimationFinishedCallback
            )
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
        val taskToMinimize = addAndGetMinimizeChangesIfNeeded(taskInfo.displayId, wct, taskInfo)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            val transition = transitions.startTransition(TRANSIT_TO_FRONT, wct, null /* handler */)
            addPendingMinimizeTransition(transition, taskToMinimize)
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
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "moveToNextDisplay: taskId=%d taskDisplayId=%d",
                taskId,
            task.displayId
        )

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
        KtProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "moveToDisplay: taskId=%d displayId=%d",
                task.taskId,
            displayId
        )

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

    /**
     * Quick-resizes a desktop task, toggling between the stable bounds and the last saved bounds
     * if available or the default bounds otherwise.
     */
    fun toggleDesktopTaskSize(taskInfo: RunningTaskInfo) {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return

        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)
        val destinationBounds = Rect()
        if (taskInfo.configuration.windowConfiguration.bounds == stableBounds) {
            // The desktop task is currently occupying the whole stable bounds. If the bounds
            // before the task was toggled to stable bounds were saved, toggle the task to those
            // bounds. Otherwise, toggle to the default bounds.
            val taskBoundsBeforeMaximize =
                    desktopModeTaskRepository.removeBoundsBeforeMaximize(taskInfo.taskId)
            if (taskBoundsBeforeMaximize != null) {
                destinationBounds.set(taskBoundsBeforeMaximize)
            } else {
                destinationBounds.set(getDefaultDesktopTaskBounds(displayLayout))
            }
        } else {
            // Save current bounds so that task can be restored back to original bounds if necessary
            // and toggle to the stable bounds.
            val taskBounds = taskInfo.configuration.windowConfiguration.bounds
            desktopModeTaskRepository.saveBoundsBeforeMaximize(taskInfo.taskId, taskBounds)
            destinationBounds.set(stableBounds)
        }

        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            toggleResizeDesktopTaskTransitionHandler.startTransition(wct)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Quick-resize to the right or left half of the stable bounds.
     *
     * @param position the portion of the screen (RIGHT or LEFT) we want to snap the task to.
     */
    fun snapToHalfScreen(taskInfo: RunningTaskInfo, position: SnapPosition) {
        val destinationBounds = getSnapBounds(taskInfo, position)

        if (destinationBounds == taskInfo.configuration.windowConfiguration.bounds) return

        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            toggleResizeDesktopTaskTransitionHandler.startTransition(wct)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    private fun getDefaultDesktopTaskBounds(displayLayout: DisplayLayout): Rect {
        // TODO(b/319819547): Account for app constraints so apps do not become letterboxed
        val desiredWidth = (displayLayout.width() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE).toInt()
        val desiredHeight = (displayLayout.height() * DESKTOP_MODE_INITIAL_BOUNDS_SCALE).toInt()
        val heightOffset = (displayLayout.height() - desiredHeight) / 2
        val widthOffset = (displayLayout.width() - desiredWidth) / 2
        return Rect(widthOffset, heightOffset,
            desiredWidth + widthOffset, desiredHeight + heightOffset)
    }

    private fun getSnapBounds(taskInfo: RunningTaskInfo, position: SnapPosition): Rect {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return Rect()

        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)

        val destinationWidth = stableBounds.width() / 2
        return when (position) {
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

    private fun bringDesktopAppsToFrontBeforeShowingNewTask(
            displayId: Int,
            wct: WindowContainerTransaction,
            newTaskIdInFront: Int
    ): RunningTaskInfo? = bringDesktopAppsToFront(displayId, wct, newTaskIdInFront)

    private fun bringDesktopAppsToFront(
            displayId: Int,
            wct: WindowContainerTransaction,
            newTaskIdInFront: Int? = null
    ): RunningTaskInfo? {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: bringDesktopAppsToFront, newTaskIdInFront=%s",
                newTaskIdInFront ?: "null")

        if (Flags.enableDesktopWindowingWallpaperActivity()) {
            // Add translucent wallpaper activity to show the wallpaper underneath
            addWallpaperActivity(wct)
        } else {
            // Move home to front
            moveHomeTaskToFront(wct)
        }

        val nonMinimizedTasksOrderedFrontToBack =
                desktopModeTaskRepository.getActiveNonMinimizedTasksOrderedFrontToBack(displayId)
        // If we're adding a new Task we might need to minimize an old one
        val taskToMinimize: RunningTaskInfo? =
                if (newTaskIdInFront != null && desktopTasksLimiter.isPresent) {
                    desktopTasksLimiter.get().getTaskToMinimizeIfNeeded(
                            nonMinimizedTasksOrderedFrontToBack, newTaskIdInFront)
                } else { null }
        nonMinimizedTasksOrderedFrontToBack
                // If there is a Task to minimize, let it stay behind the Home Task
                .filter { taskId -> taskId != taskToMinimize?.taskId }
                .mapNotNull { taskId -> shellTaskOrganizer.getRunningTaskInfo(taskId) }
                .reversed() // Start from the back so the front task is brought forward last
                .forEach { task -> wct.reorder(task.token, true /* onTop */) }
        return taskToMinimize
    }

    private fun moveHomeTaskToFront(wct: WindowContainerTransaction) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), true /* onTop */) }
    }

    private fun addWallpaperActivity(wct: WindowContainerTransaction) {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: addWallpaper")
        val intent = Intent(context, DesktopWallpaperActivity::class.java)
        val options = ActivityOptions.makeBasic().apply {
            isPendingIntentBackgroundActivityLaunchAllowedByPermission = true
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val pendingIntent = PendingIntent.getActivity(context, /* requestCode = */ 0, intent,
            PendingIntent.FLAG_IMMUTABLE)
        wct.sendPendingIntent(pendingIntent, intent, options.toBundle())
    }

    private fun removeWallpaperActivity(wct: WindowContainerTransaction) {
        desktopModeTaskRepository.wallpaperActivityToken?.let { token ->
            KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: removeWallpaper")
            wct.removeTask(token)
        }
    }

    fun releaseVisualIndicator() {
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
                // Handle back navigation for the last window if wallpaper available
                shouldRemoveWallpaper(request) ->
                    true
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
                request.type == TRANSIT_TO_BACK -> handleBackNavigation(task)
                // If display has tasks stashed, handle as stashed launch
                task.isStashed -> handleStashedTaskLaunch(task, transition)
                // Check if the task has a top transparent activity
                shouldLaunchAsModal(task) -> handleTransparentTaskLaunch(task)
                // Check if fullscreen task should be updated
                task.isFullscreen -> handleFullscreenTaskLaunch(task, transition)
                // Check if freeform task should be updated
                task.isFreeform -> handleFreeformTaskLaunch(task, transition)
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

    private val TaskInfo.isStashed: Boolean
        get() = desktopModeTaskRepository.isStashed(displayId)

    private fun shouldLaunchAsModal(task: TaskInfo): Boolean {
        return Flags.enableDesktopWindowingModalsPolicy() && isSingleTopActivityTranslucent(task)
    }

    private fun shouldRemoveWallpaper(request: TransitionRequestInfo): Boolean {
        return Flags.enableDesktopWindowingWallpaperActivity() &&
                request.type == TRANSIT_TO_BACK &&
                request.triggerTask?.let { task ->
                    desktopModeTaskRepository.isOnlyActiveTask(task.taskId)
                } ?: false
    }

    private fun handleFreeformTaskLaunch(
            task: RunningTaskInfo,
            transition: IBinder
    ): WindowContainerTransaction? {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: handleFreeformTaskLaunch")
        if (!desktopModeTaskRepository.isDesktopModeShowing(task.displayId)) {
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
        // Desktop Mode is showing and we're launching a new Task - we might need to minimize
        // a Task.
        val wct = WindowContainerTransaction()
        val taskToMinimize = addAndGetMinimizeChangesIfNeeded(task.displayId, wct, task)
        if (taskToMinimize != null) {
            addPendingMinimizeTransition(transition, taskToMinimize)
            return wct
        }
        return null
    }

    private fun handleFullscreenTaskLaunch(
            task: RunningTaskInfo,
            transition: IBinder
    ): WindowContainerTransaction? {
        KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "DesktopTasksController: handleFullscreenTaskLaunch")
        if (desktopModeTaskRepository.isDesktopModeShowing(task.displayId)) {
            KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController: switch fullscreen task to freeform on transition" +
                            " taskId=%d",
                    task.taskId
            )
            return WindowContainerTransaction().also { wct ->
                addMoveToDesktopChanges(wct, task)
                // Desktop Mode is already showing and we're launching a new Task - we might need to
                // minimize another Task.
                val taskToMinimize = addAndGetMinimizeChangesIfNeeded(task.displayId, wct, task)
                addPendingMinimizeTransition(transition, taskToMinimize)
            }
        }
        return null
    }

    private fun handleStashedTaskLaunch(
            task: RunningTaskInfo,
            transition: IBinder
    ): WindowContainerTransaction {
        KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "DesktopTasksController: launch apps with stashed on transition taskId=%d",
                task.taskId
        )
        val wct = WindowContainerTransaction()
        val taskToMinimize =
                bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
        addMoveToDesktopChanges(wct, task)
        desktopModeTaskRepository.setStashed(task.displayId, false)
        addPendingMinimizeTransition(transition, taskToMinimize)
        return wct
    }

    // Always launch transparent tasks in fullscreen.
    private fun handleTransparentTaskLaunch(task: RunningTaskInfo): WindowContainerTransaction? {
        // Already fullscreen, no-op.
        if (task.isFullscreen)
            return null
        return WindowContainerTransaction().also { wct ->
            addMoveToFullscreenChanges(wct, task)
        }
    }

    /** Handle back navigation by removing wallpaper activity if it's the last active task */
    private fun handleBackNavigation(task: RunningTaskInfo): WindowContainerTransaction? {
        if (desktopModeTaskRepository.isOnlyActiveTask(task.taskId) &&
            desktopModeTaskRepository.wallpaperActivityToken != null) {
            // Remove wallpaper activity when the last active task is removed
            return WindowContainerTransaction().also { wct ->
                removeWallpaperActivity(wct)
            }
        } else {
            return null
        }
    }

    private fun addMoveToDesktopChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ) {
        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId)!!
        val tdaWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        val targetWindowingMode = if (tdaWindowingMode == WINDOWING_MODE_FREEFORM) {
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
        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId)!!
        val tdaWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        val targetWindowingMode = if (tdaWindowingMode == WINDOWING_MODE_FULLSCREEN) {
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
        // This windowing mode is to get the transition animation started; once we complete
        // split select, we will change windowing mode to undefined and inherit from split stage.
        // Going to undefined here causes task to flicker to the top left.
        // Cancelling the split select flow will revert it to fullscreen.
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_MULTI_WINDOW)
        // The task's density may have been overridden in freeform; revert it here as we don't
        // want it overridden in multi-window.
        wct.setDensityDpi(taskInfo.token, getDefaultDensityDpi())
    }

    /** Returns the ID of the Task that will be minimized, or null if no task will be minimized. */
    private fun addAndGetMinimizeChangesIfNeeded(
            displayId: Int,
            wct: WindowContainerTransaction,
            newTaskInfo: RunningTaskInfo
    ): RunningTaskInfo? {
        if (!desktopTasksLimiter.isPresent) return null
        return desktopTasksLimiter.get().addAndGetMinimizeTaskChangesIfNeeded(
                displayId, wct, newTaskInfo)
    }

    private fun addPendingMinimizeTransition(
            transition: IBinder,
            taskToMinimize: RunningTaskInfo?
    ) {
        if (taskToMinimize == null) return
        desktopTasksLimiter.ifPresent {
            it.addPendingMinimizeChange(
                    transition, taskToMinimize.displayId, taskToMinimize.taskId)
        }
    }

    /** Enter split by using the focused desktop task in given `displayId`. */
    fun enterSplit(
        displayId: Int,
        leftOrTop: Boolean
    ) {
        getFocusedFreeformTask(displayId)?.let { requestSplit(it, leftOrTop) }
    }

    private fun getFocusedFreeformTask(displayId: Int): RunningTaskInfo? {
        return shellTaskOrganizer.getRunningTasks(displayId)
                .find { taskInfo -> taskInfo.isFocused &&
                        taskInfo.windowingMode == WINDOWING_MODE_FREEFORM }
    }

    /**
     * Requests a task be transitioned from desktop to split select. Applies needed windowing
     * changes if this transition is enabled.
     */
    @JvmOverloads
    fun requestSplit(
        taskInfo: RunningTaskInfo,
        leftOrTop: Boolean = false,
    ) {
        val windowingMode = taskInfo.windowingMode
        if (windowingMode == WINDOWING_MODE_FULLSCREEN || windowingMode == WINDOWING_MODE_FREEFORM
        ) {
            val wct = WindowContainerTransaction()
            addMoveToSplitChanges(wct, taskInfo)
            splitScreenController.requestEnterSplitSelect(
                taskInfo,
                wct,
                if (leftOrTop) SPLIT_POSITION_TOP_OR_LEFT else SPLIT_POSITION_BOTTOM_OR_RIGHT,
                taskInfo.configuration.windowConfiguration.bounds
            )
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
    ): DesktopModeVisualIndicator.IndicatorType {
        // If the visual indicator does not exist, create it.
        val indicator = visualIndicator ?: DesktopModeVisualIndicator(
            syncQueue, taskInfo, displayController, context, taskSurface,
            rootTaskDisplayAreaOrganizer)
        if (visualIndicator == null) visualIndicator = indicator
        return indicator.updateIndicatorType(PointF(inputX, taskTop), taskInfo.windowingMode)
    }

    /**
     * Perform checks required on drag end. If indicator indicates a windowing mode change, perform
     * that change. Otherwise, ensure bounds are up to date.
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
        taskBounds: Rect,
        validDragArea: Rect
    ) {
        if (taskInfo.configuration.windowConfiguration.windowingMode != WINDOWING_MODE_FREEFORM) {
            return
        }

        val indicator = visualIndicator ?: return
        val indicatorType = indicator.updateIndicatorType(
            PointF(inputCoordinate.x, taskBounds.top.toFloat()),
            taskInfo.windowingMode
        )
        when (indicatorType) {
            DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                moveToFullscreenWithAnimation(taskInfo, position)
            }
            DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                releaseVisualIndicator()
                snapToHalfScreen(taskInfo, SnapPosition.LEFT)
            }
            DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                releaseVisualIndicator()
                snapToHalfScreen(taskInfo, SnapPosition.RIGHT)
            }
            DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR -> {
                // If task bounds are outside valid drag area, snap them inward and perform a
                // transaction to set bounds.
                if (DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(
                        taskBounds, validDragArea)) {
                    val wct = WindowContainerTransaction()
                    wct.setBounds(taskInfo.token, taskBounds)
                    transitions.startTransition(TRANSIT_CHANGE, wct, null)
                }
                releaseVisualIndicator()
            }
            DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR -> {
                throw IllegalArgumentException("Should not be receiving TO_DESKTOP_INDICATOR for " +
                        "a freeform task.")
            }
        }
        // A freeform drag-move ended, remove the indicator immediately.
        releaseVisualIndicator()
    }

    /**
     * Perform checks required when drag ends under status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param y height of drag, to be checked against status bar height.
     */
    fun onDragPositioningEndThroughStatusBar(inputCoordinates: PointF, taskInfo: RunningTaskInfo) {
        val indicator = visualIndicator ?: return
        val indicatorType = indicator
            .updateIndicatorType(inputCoordinates, taskInfo.windowingMode)
        when (indicatorType) {
            DesktopModeVisualIndicator.IndicatorType.TO_DESKTOP_INDICATOR -> {
                val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return
                finalizeDragToDesktop(taskInfo, getDefaultDesktopTaskBounds(displayLayout))
            }
            DesktopModeVisualIndicator.IndicatorType.NO_INDICATOR,
            DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                cancelDragToDesktop(taskInfo)
            }
            DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                finalizeDragToDesktop(taskInfo, getSnapBounds(taskInfo, SnapPosition.LEFT))
            }
            DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                finalizeDragToDesktop(taskInfo, getSnapBounds(taskInfo, SnapPosition.RIGHT))
            }
        }
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

    override fun onUnhandledDrag(
        launchIntent: PendingIntent,
        dragSurface: SurfaceControl,
        onFinishCallback: Consumer<Boolean>
    ): Boolean {
        // TODO(b/320797628): Pass through which display we are dropping onto
        val activeTasks = desktopModeTaskRepository.getActiveTasks(DEFAULT_DISPLAY)
        if (!activeTasks.any { desktopModeTaskRepository.isVisibleTask(it) }) {
            // Not currently in desktop mode, ignore the drop
            return false
        }

        val launchComponent = getComponent(launchIntent)
        if (!multiInstanceHelper.supportsMultiInstanceSplit(launchComponent)) {
            // TODO(b/320797628): Should only return early if there is an existing running task, and
            //                    notify the user as well. But for now, just ignore the drop.
            KtProtoLog.v(WM_SHELL_DESKTOP_MODE, "Dropped intent does not support multi-instance")
            return false
        }

        // Start a new transition to launch the app
        val opts = ActivityOptions.makeBasic().apply {
            launchWindowingMode = WINDOWING_MODE_FREEFORM
            pendingIntentLaunchFlags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED
            )
            isPendingIntentBackgroundActivityLaunchAllowedByPermission = true
        }
        val wct = WindowContainerTransaction()
        wct.sendPendingIntent(launchIntent, null, opts.toBundle())
        transitions.startTransition(TRANSIT_OPEN, wct, null /* handler */)

        // Report that this is handled by the listener
        onFinishCallback.accept(true)

        // We've assumed responsibility of cleaning up the drag surface, so do that now
        // TODO(b/320797628): Do an actual animation here for the drag surface
        val t = SurfaceControl.Transaction()
        t.remove(dragSurface)
        t.apply()
        return true
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

        override fun moveFocusedTaskToDesktop(displayId: Int) {
            mainExecutor.execute {
                this@DesktopTasksController.moveFocusedTaskToDesktop(displayId)
            }
        }

        override fun moveFocusedTaskToFullscreen(displayId: Int) {
            mainExecutor.execute {
                this@DesktopTasksController.enterFullscreen(displayId)
            }
        }

        override fun moveFocusedTaskToStageSplit(displayId: Int, leftOrTop: Boolean) {
            mainExecutor.execute {
                this@DesktopTasksController.enterSplit(displayId, leftOrTop)
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
            override fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {
                KtProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onVisibilityChanged display=%d visible=%d",
                        displayId,
                        visibleTasksCount
                )
                remoteListener.call {
                    l -> l.onTasksVisibilityChanged(displayId, visibleTasksCount)
                }
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

        override fun moveToDesktop(taskId: Int) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "moveToDesktop"
            ) { c -> c.moveToDesktop(taskId) }
        }
    }

    companion object {
        private val DESKTOP_DENSITY_OVERRIDE =
            SystemProperties.getInt("persist.wm.debug.desktop_mode_density", 284)
        private val DESKTOP_DENSITY_ALLOWED_RANGE = (100..1000)

        @JvmField
        val DESKTOP_MODE_INITIAL_BOUNDS_SCALE = SystemProperties
                .getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f

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
