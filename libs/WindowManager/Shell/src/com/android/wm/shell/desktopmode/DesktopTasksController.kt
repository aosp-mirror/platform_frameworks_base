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

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.KeyguardManager
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
import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyGestureEventHandler
import android.hardware.input.KeyGestureEvent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.SystemProperties
import android.util.Size
import android.view.Display.DEFAULT_DISPLAY
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.DesktopModeFlags
import android.window.DesktopModeFlags.DISABLE_NON_RESIZABLE_APP_SNAP_RESIZE
import android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
import android.window.DesktopModeFlags.ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
import android.window.RemoteTransition
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.hardware.input.Flags.useKeyGestureEventHandler
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_SNAP_RESIZE
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.policy.ScreenDecorationsUtils
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.window.flags.Flags.enableMoveToNextDisplayShortcut
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.LaunchAdjacentController
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.MultiInstanceHelper.Companion.getComponent
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SingleInstanceRemoteListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.compatui.isTopActivityExemptFromDesktopWindowing
import com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.DragStartState
import com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.IndicatorType
import com.android.wm.shell.desktopmode.DesktopRepository.VisibleTasksListener
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler.DragToDesktopStateListener
import com.android.wm.shell.desktopmode.minimize.DesktopWindowLimitRemoteHandler
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.recents.RecentTasksController
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.shared.ShellSharedConstants
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.shared.annotations.ExternalThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus.DESKTOP_DENSITY_OVERRIDE
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus.useDesktopOverrideDensity
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.UserChangeListener
import com.android.wm.shell.transition.FocusTransitionObserver
import com.android.wm.shell.transition.OneShotRemoteHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator
import com.android.wm.shell.windowdecor.OnTaskRepositionAnimationListener
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener
import com.android.wm.shell.windowdecor.extension.isFullscreen
import com.android.wm.shell.windowdecor.extension.isMultiWindow
import com.android.wm.shell.windowdecor.extension.requestingImmersive
import com.android.wm.shell.windowdecor.tiling.DesktopTilingDecorViewModel
import java.io.PrintWriter
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.Consumer
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
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
    private val keyguardManager: KeyguardManager,
    private val returnToDragStartAnimator: ReturnToDragStartAnimator,
    private val enterDesktopTaskTransitionHandler: EnterDesktopTaskTransitionHandler,
    private val exitDesktopTaskTransitionHandler: ExitDesktopTaskTransitionHandler,
    private val desktopModeDragAndDropTransitionHandler: DesktopModeDragAndDropTransitionHandler,
    private val toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler,
    private val dragToDesktopTransitionHandler: DragToDesktopTransitionHandler,
    private val desktopImmersiveController: DesktopImmersiveController,
    private val taskRepository: DesktopRepository,
    private val desktopModeLoggerTransitionObserver: DesktopModeLoggerTransitionObserver,
    private val launchAdjacentController: LaunchAdjacentController,
    private val recentsTransitionHandler: RecentsTransitionHandler,
    private val multiInstanceHelper: MultiInstanceHelper,
    @ShellMainThread private val mainExecutor: ShellExecutor,
    private val desktopTasksLimiter: Optional<DesktopTasksLimiter>,
    private val recentTasksController: RecentTasksController?,
    private val interactionJankMonitor: InteractionJankMonitor,
    @ShellMainThread private val handler: Handler,
    private val inputManager: InputManager,
    private val focusTransitionObserver: FocusTransitionObserver,
    private val desktopModeEventLogger: DesktopModeEventLogger,
    private val desktopTilingDecorViewModel: DesktopTilingDecorViewModel,
) :
    RemoteCallable<DesktopTasksController>,
    Transitions.TransitionHandler,
    DragAndDropController.DragAndDropListener,
    UserChangeListener,
    KeyGestureEventHandler {

    private val desktopMode: DesktopModeImpl
    private var visualIndicator: DesktopModeVisualIndicator? = null
    private var userId: Int
    private val desktopModeShellCommandHandler: DesktopModeShellCommandHandler =
        DesktopModeShellCommandHandler(this)
    private val mOnAnimationFinishedCallback =
        Consumer<SurfaceControl.Transaction> { t: SurfaceControl.Transaction ->
            visualIndicator?.releaseVisualIndicator(t)
            visualIndicator = null
        }
    private val dragToDesktopStateListener =
        object : DragToDesktopStateListener {
            override fun onCommitToDesktopAnimationStart(tx: SurfaceControl.Transaction) {
                removeVisualIndicator(tx)
            }

            override fun onCancelToDesktopAnimationEnd(tx: SurfaceControl.Transaction) {
                removeVisualIndicator(tx)
            }

            private fun removeVisualIndicator(tx: SurfaceControl.Transaction) {
                visualIndicator?.fadeOutIndicator {
                    visualIndicator?.releaseVisualIndicator(tx)
                    visualIndicator = null
                }
            }
        }

    @VisibleForTesting
    var taskbarDesktopTaskListener: TaskbarDesktopTaskListener? = null

    /** Task id of the task currently being dragged from fullscreen/split. */
    val draggingTaskId
        get() = dragToDesktopTransitionHandler.draggingTaskId

    private var recentsAnimationRunning = false
    private lateinit var splitScreenController: SplitScreenController
    lateinit var freeformTaskTransitionStarter: FreeformTaskTransitionStarter
    // Launch cookie used to identify a drag and drop transition to fullscreen after it has begun.
    // Used to prevent handleRequest from moving the new fullscreen task to freeform.
    private var dragAndDropFullscreenCookie: Binder? = null

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            shellInit.addInitCallback({ onInit() }, this)
        }
        userId = ActivityManager.getCurrentUser()
    }

    private fun onInit() {
        logD("onInit")
        shellCommandHandler.addDumpCallback(this::dump, this)
        shellCommandHandler.addCommandCallback("desktopmode", desktopModeShellCommandHandler, this)
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
        shellController.addUserChangeListener(this)
        transitions.addHandler(this)
        dragToDesktopTransitionHandler.dragToDesktopStateListener = dragToDesktopStateListener
        recentsTransitionHandler.addTransitionStateListener(
            object : RecentsTransitionStateListener {
                override fun onAnimationStateChanged(running: Boolean) {
                    logV("Recents animation state changed running=%b", running)
                    recentsAnimationRunning = running
                    desktopTilingDecorViewModel.onOverviewAnimationStateChange(running)
                }
            }
        )
        dragAndDropController.addListener(this)
        if (useKeyGestureEventHandler() && enableMoveToNextDisplayShortcut()) {
            inputManager.registerKeyGestureEventHandler(this)
        }
    }

    @VisibleForTesting
    fun getVisualIndicator(): DesktopModeVisualIndicator? {
        return visualIndicator
    }

    fun setOnTaskResizeAnimationListener(listener: OnTaskResizeAnimationListener) {
        toggleResizeDesktopTaskTransitionHandler.setOnTaskResizeAnimationListener(listener)
        enterDesktopTaskTransitionHandler.setOnTaskResizeAnimationListener(listener)
        dragToDesktopTransitionHandler.onTaskResizeAnimationListener = listener
        desktopImmersiveController.onTaskResizeAnimationListener = listener
    }

    fun setOnTaskRepositionAnimationListener(listener: OnTaskRepositionAnimationListener) {
        returnToDragStartAnimator.setTaskRepositionAnimationListener(listener)
    }

    /** Setter needed to avoid cyclic dependency. */
    fun setSplitScreenController(controller: SplitScreenController) {
        splitScreenController = controller
        dragToDesktopTransitionHandler.setSplitScreenController(controller)
    }

    /** Returns the transition type for the given remote transition. */
    private fun transitionType(remoteTransition: RemoteTransition?): Int {
        if (remoteTransition == null) {
            logV("RemoteTransition is null")
            return TRANSIT_NONE
        }
        return TRANSIT_TO_FRONT
    }

    /** Show all tasks, that are part of the desktop, on top of launcher */
    fun showDesktopApps(displayId: Int, remoteTransition: RemoteTransition? = null) {
        logV("showDesktopApps")
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(displayId, wct)

        val transitionType = transitionType(remoteTransition)
        val handler = remoteTransition?.let {
            OneShotRemoteHandler(transitions.mainExecutor, remoteTransition)
        }
        transitions.startTransition(transitionType, wct, handler).also { t ->
            handler?.setTransition(t)
        }
    }

    /** Gets number of visible tasks in [displayId]. */
    fun visibleTaskCount(displayId: Int): Int =
        taskRepository.getVisibleTaskCount(displayId)

    /** Returns true if any tasks are visible in Desktop Mode. */
    fun isDesktopModeShowing(displayId: Int): Boolean = visibleTaskCount(displayId) > 0

    /** Moves focused task to desktop mode for given [displayId]. */
    fun moveFocusedTaskToDesktop(displayId: Int, transitionSource: DesktopModeTransitionSource) {
        val allFocusedTasks = getAllFocusedTasks(displayId)
        when (allFocusedTasks.size) {
            0 -> return
            // Full screen case
            1 -> moveRunningTaskToDesktop(
                allFocusedTasks.single(), transitionSource = transitionSource)
            // Split-screen case where there are two focused tasks, then we find the child
            // task to move to desktop.
            2 -> moveRunningTaskToDesktop(
                getSplitFocusedTask(allFocusedTasks[0], allFocusedTasks[1]),
                    transitionSource = transitionSource)
            else -> logW(
                "DesktopTasksController: Cannot enter desktop, expected less " +
                "than 3 focused tasks but found %d", allFocusedTasks.size)
        }
    }

    /**
     * Returns all focused tasks in full screen or split screen mode in [displayId] when
     * it is not the home activity.
     */
    private fun getAllFocusedTasks(displayId: Int): List<RunningTaskInfo> =
        shellTaskOrganizer.getRunningTasks(displayId).filter {
            it.isFocused &&
            (it.windowingMode == WINDOWING_MODE_FULLSCREEN ||
                it.windowingMode == WINDOWING_MODE_MULTI_WINDOW) &&
            it.activityType != ACTIVITY_TYPE_HOME
        }

    /** Returns child task from two focused tasks in split screen mode. */
    private fun getSplitFocusedTask(task1: RunningTaskInfo, task2: RunningTaskInfo) =
        if (task1.taskId == task2.parentTaskId) task2 else task1

    private fun forceEnterDesktop(displayId: Int): Boolean {
        if (!DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(context)) {
            return false
        }

        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)
        requireNotNull(tdaInfo) {
            "This method can only be called with the ID of a display having non-null DisplayArea."
        }
        val tdaWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        val isFreeformDisplay = tdaWindowingMode == WINDOWING_MODE_FREEFORM
        return isFreeformDisplay
    }

    /** Moves task to desktop mode if task is running, else launches it in desktop mode. */
    fun moveTaskToDesktop(
        taskId: Int,
        wct: WindowContainerTransaction = WindowContainerTransaction(),
        transitionSource: DesktopModeTransitionSource,
    ): Boolean {
        val runningTask = shellTaskOrganizer.getRunningTaskInfo(taskId)
        if (runningTask == null) {
            return moveBackgroundTaskToDesktop(taskId, wct, transitionSource)
        }
        moveRunningTaskToDesktop(runningTask, wct, transitionSource)
        return true
    }

    private fun moveBackgroundTaskToDesktop(
            taskId: Int,
            wct: WindowContainerTransaction,
            transitionSource: DesktopModeTransitionSource,
    ): Boolean {
        if (recentTasksController?.findTaskInBackground(taskId) == null) {
            logW("moveBackgroundTaskToDesktop taskId=%d not found", taskId)
            return false
        }
        logV("moveBackgroundTaskToDesktop with taskId=%d", taskId)
        // TODO(342378842): Instead of using default display, support multiple displays
        val taskIdToMinimize = bringDesktopAppsToFrontBeforeShowingNewTask(
            DEFAULT_DISPLAY, wct, taskId)
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
            wct = wct,
            displayId = DEFAULT_DISPLAY,
            excludeTaskId = taskId,
        )
        wct.startTask(
            taskId,
            ActivityOptions.makeBasic().apply {
                launchWindowingMode = WINDOWING_MODE_FREEFORM
            }.toBundle(),
        )
        // TODO(343149901): Add DPI changes for task launch
        val transition = enterDesktopTaskTransitionHandler.moveToDesktop(wct, transitionSource)
        taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
        runOnTransit?.invoke(transition)
        return true
    }

   /** Moves a running task to desktop. */
    fun moveRunningTaskToDesktop(
        task: RunningTaskInfo,
        wct: WindowContainerTransaction = WindowContainerTransaction(),
        transitionSource: DesktopModeTransitionSource,
    ) {
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue()
            && isTopActivityExemptFromDesktopWindowing(context, task)) {
            logW("Cannot enter desktop for taskId %d, ineligible top activity found", task.taskId)
            return
        }
        logV("moveRunningTaskToDesktop taskId=%d", task.taskId)
        exitSplitIfApplicable(wct, task)
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
            wct = wct,
            displayId = task.displayId,
            excludeTaskId = task.taskId,
        )
        // Bring other apps to front first
        val taskIdToMinimize =
            bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
        addMoveToDesktopChanges(wct, task)

        val transition = enterDesktopTaskTransitionHandler.moveToDesktop(wct, transitionSource)
        taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
        runOnTransit?.invoke(transition)
    }

    /**
     * The first part of the animated drag to desktop transition. This is followed with a call to
     * [finalizeDragToDesktop] or [cancelDragToDesktop].
     */
    fun startDragToDesktop(
        taskInfo: RunningTaskInfo,
        dragToDesktopValueAnimator: MoveToDesktopAnimator,
        taskSurface: SurfaceControl,
    ) {
        logV("startDragToDesktop taskId=%d", taskInfo.taskId)
        interactionJankMonitor.begin(taskSurface, context, handler,
            CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD)
        dragToDesktopTransitionHandler.startDragToDesktopTransition(
            taskInfo.taskId,
            dragToDesktopValueAnimator
        )
    }

    /**
     * The second part of the animated drag to desktop transition, called after
     * [startDragToDesktop].
     */
    private fun finalizeDragToDesktop(taskInfo: RunningTaskInfo) {
        ProtoLog.v(
            WM_SHELL_DESKTOP_MODE,
            "DesktopTasksController: finalizeDragToDesktop taskId=%d",
            taskInfo.taskId
        )
        val wct = WindowContainerTransaction()
        exitSplitIfApplicable(wct, taskInfo)
        moveHomeTask(wct, toTop = true)
        val taskIdToMinimize =
            bringDesktopAppsToFrontBeforeShowingNewTask(taskInfo.displayId, wct, taskInfo.taskId)
        addMoveToDesktopChanges(wct, taskInfo)
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
            wct, taskInfo.displayId)
        val transition = dragToDesktopTransitionHandler.finishDragToDesktopTransition(wct)
        transition?.let {
            taskIdToMinimize?.let { taskId -> addPendingMinimizeTransition(it, taskId) }
            runOnTransit?.invoke(transition)
        }
    }

    /**
     * Perform needed cleanup transaction once animation is complete. Bounds need to be set here
     * instead of initial wct to both avoid flicker and to have task bounds to use for the staging
     * animation.
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
     * Perform clean up of the desktop wallpaper activity if the closed window task is the last
     * active task.
     *
     * @param wct transaction to modify if the last active task is closed
     * @param displayId display id of the window that's being closed
     * @param taskId task id of the window that's being closed
     */
    fun onDesktopWindowClose(
        wct: WindowContainerTransaction,
        displayId: Int,
        taskInfo: RunningTaskInfo,
    ): ((IBinder) -> Unit)? {
        val taskId = taskInfo.taskId
        desktopTilingDecorViewModel.removeTaskIfTiled(displayId, taskId)
        if (taskRepository.isOnlyVisibleNonClosingTask(taskId)) {
            removeWallpaperActivity(wct)
        }
        taskRepository.addClosingTask(displayId, taskId)
        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(
                displayId,
                taskId
            )
        )
        return desktopImmersiveController.exitImmersiveIfApplicable(wct, taskInfo)
    }

    fun minimizeTask(taskInfo: RunningTaskInfo) {
        val taskId = taskInfo.taskId
        val displayId = taskInfo.displayId
        val wct = WindowContainerTransaction()
        if (taskRepository.isOnlyVisibleNonClosingTask(taskId)) {
            // Perform clean up of the desktop wallpaper activity if the minimized window task is
            // the last active task.
            removeWallpaperActivity(wct)
        }
        // Notify immersive handler as it might need to exit immersive state.
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(wct, taskInfo)

        wct.reorder(taskInfo.token, false)
        val transition = freeformTaskTransitionStarter.startMinimizedModeTransition(wct)
        desktopTasksLimiter.ifPresent {
            it.addPendingMinimizeChange(
                transition = transition,
                displayId = displayId,
                taskId = taskId
            )
        }
        runOnTransit?.invoke(transition)
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int, transitionSource: DesktopModeTransitionSource) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task ->
            desktopTilingDecorViewModel.removeTaskIfTiled(task.displayId, taskId)
            moveToFullscreenWithAnimation(task, task.positionInParent, transitionSource)
        }
    }

    /** Enter fullscreen by moving the focused freeform task in given `displayId` to fullscreen. */
    fun enterFullscreen(displayId: Int, transitionSource: DesktopModeTransitionSource) {
        getFocusedFreeformTask(displayId)?.let {
            desktopTilingDecorViewModel.removeTaskIfTiled(displayId, it.taskId)
            moveToFullscreenWithAnimation(it, it.positionInParent, transitionSource)
        }
    }

    /** Move a desktop app to split screen. */
    fun moveToSplit(task: RunningTaskInfo) {
        logV( "moveToSplit taskId=%s", task.taskId)
        desktopTilingDecorViewModel.removeTaskIfTiled(task.displayId, task.taskId)
        val wct = WindowContainerTransaction()
        wct.setBounds(task.token, Rect())
        // Rather than set windowing mode to multi-window at task level, set it to
        // undefined and inherit from split stage.
        wct.setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)

        transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
    }

    private fun exitSplitIfApplicable(wct: WindowContainerTransaction, taskInfo: RunningTaskInfo) {
        if (splitScreenController.isTaskInSplitScreen(taskInfo.taskId)) {
            splitScreenController.prepareExitSplitScreen(
                wct,
                splitScreenController.getStageOfTask(taskInfo.taskId),
                EXIT_REASON_DESKTOP_MODE
            )
            splitScreenController.transitionHandler?.onSplitToDesktop()
        }
    }

    /**
     * The second part of the animated drag to desktop transition, called after
     * [startDragToDesktop].
     */
    fun cancelDragToDesktop(task: RunningTaskInfo) {
        logV("cancelDragToDesktop taskId=%d", task.taskId)
        dragToDesktopTransitionHandler.cancelDragToDesktopTransition(
            DragToDesktopTransitionHandler.CancelState.STANDARD_CANCEL
        )
    }

    private fun moveToFullscreenWithAnimation(
        task: RunningTaskInfo,
        position: Point,
        transitionSource: DesktopModeTransitionSource
    ) {
        logV("moveToFullscreenWithAnimation taskId=%d", task.taskId)
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task)

        exitDesktopTaskTransitionHandler.startTransition(
                transitionSource,
                wct,
                position,
                mOnAnimationFinishedCallback
            )
    }

    /**
     * Move a task to the front, using [remoteTransition].
     *
     * Note: beyond moving a task to the front, this method will minimize a task if we reach the
     * Desktop task limit, so [remoteTransition] should also handle any such minimize change.
     */
    @JvmOverloads
    fun moveTaskToFront(taskId: Int, remoteTransition: RemoteTransition? = null) {
        val task = shellTaskOrganizer.getRunningTaskInfo(taskId)
        if (task == null) {
            moveBackgroundTaskToFront(taskId, remoteTransition)
        } else {
            moveTaskToFront(task, remoteTransition)
        }
    }

    /**
     * Launch a background task in desktop. Note that this should be used when we are already in
     * desktop. If outside of desktop and want to launch a background task in desktop, use
     * [moveBackgroundTaskToDesktop] instead.
     */
    private fun moveBackgroundTaskToFront(taskId: Int, remoteTransition: RemoteTransition?) {
        logV("moveBackgroundTaskToFront taskId=%s", taskId)
        val wct = WindowContainerTransaction()
        // TODO: b/342378842 - Instead of using default display, support multiple displays
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
            wct = wct,
            displayId = DEFAULT_DISPLAY,
            excludeTaskId = taskId,
        )
        wct.startTask(
            taskId,
            ActivityOptions.makeBasic().apply {
                launchWindowingMode = WINDOWING_MODE_FREEFORM
            }.toBundle(),
        )
        val transition = startLaunchTransition(TRANSIT_OPEN, wct, taskId, remoteTransition)
        runOnTransit?.invoke(transition)
    }

    /**
     * Move a task to the front, using [remoteTransition].
     *
     * Note: beyond moving a task to the front, this method will minimize a task if we reach the
     * Desktop task limit, so [remoteTransition] should also handle any such minimize change.
     */
    @JvmOverloads
    fun moveTaskToFront(taskInfo: RunningTaskInfo, remoteTransition: RemoteTransition? = null) {
        logV("moveTaskToFront taskId=%s", taskInfo.taskId)
        // If a task is tiled, another task should be brought to foreground with it so let
        // tiling controller handle the request.
        if (desktopTilingDecorViewModel.moveTaskToFrontIfTiled(taskInfo)) {
            return
        }
        val wct = WindowContainerTransaction()
        wct.reorder(taskInfo.token, true /* onTop */, true /* includingParents */)
        val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
            wct = wct,
            displayId = taskInfo.displayId,
            excludeTaskId = taskInfo.taskId,
        )
        val transition =
            startLaunchTransition(
                TRANSIT_TO_FRONT,
                wct,
                taskInfo.taskId,
                remoteTransition,
                taskInfo.displayId
            )
        runOnTransit?.invoke(transition)
    }

    private fun startLaunchTransition(
        transitionType: Int,
        wct: WindowContainerTransaction,
        taskId: Int,
        remoteTransition: RemoteTransition?,
        displayId: Int = DEFAULT_DISPLAY,
    ): IBinder {
        val taskIdToMinimize = addAndGetMinimizeChanges(displayId, wct, taskId)
        if (remoteTransition == null) {
            val t = transitions.startTransition(transitionType, wct, null /* handler */)
            taskIdToMinimize?.let { addPendingMinimizeTransition(t, it) }
            return t
        }
        if (taskIdToMinimize == null) {
            val remoteTransitionHandler = OneShotRemoteHandler(mainExecutor, remoteTransition)
            val t = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
            remoteTransitionHandler.setTransition(t)
            return t
        }
        val remoteTransitionHandler =
            DesktopWindowLimitRemoteHandler(
                mainExecutor, rootTaskDisplayAreaOrganizer, remoteTransition, taskIdToMinimize)
        val t = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
        remoteTransitionHandler.setTransition(t)
        taskIdToMinimize.let { addPendingMinimizeTransition(t, it) }
        return t
    }

    /**
     * Move task to the next display.
     *
     * Queries all current known display ids and sorts them in ascending order. Then iterates
     * through the list and looks for the display id that is larger than the display id for the
     * passed in task. If a display with a higher id is not found, iterates through the list and
     * finds the first display id that is not the display id for the passed in task.
     *
     * If a display matching the above criteria is found, re-parents the task to that display. No-op
     * if no such display is found.
     */
    fun moveToNextDisplay(taskId: Int) {
        val task = shellTaskOrganizer.getRunningTaskInfo(taskId)
        if (task == null) {
            logW("moveToNextDisplay: taskId=%d not found", taskId)
            return
        }
        logV("moveToNextDisplay: taskId=%d displayId=%d", taskId, task.displayId)

        val displayIds = rootTaskDisplayAreaOrganizer.displayIds.sorted()
        // Get the first display id that is higher than current task display id
        var newDisplayId = displayIds.firstOrNull { displayId -> displayId > task.displayId }
        if (newDisplayId == null) {
            // No display with a higher id, get the first display id that is not the task display id
            newDisplayId = displayIds.firstOrNull { displayId -> displayId < task.displayId }
        }
        if (newDisplayId == null) {
            logW("moveToNextDisplay: next display not found")
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
        logV("moveToDisplay: taskId=%d displayId=%d", task.taskId, displayId)
        if (task.displayId == displayId) {
            logD("moveToDisplay: task already on display %d", displayId)
            return
        }

        val displayAreaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)
        if (displayAreaInfo == null) {
            logW("moveToDisplay: display not found")
            return
        }

        val wct = WindowContainerTransaction()
        if (!task.isFreeform) addMoveToDesktopChanges(wct, task, displayId)
        wct.reparent(task.token, displayAreaInfo.token, true /* onTop */)

        transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
    }

    /** Moves a task in/out of full immersive state within the desktop. */
    fun toggleDesktopTaskFullImmersiveState(taskInfo: RunningTaskInfo) {
        if (taskRepository.isTaskInFullImmersiveState(taskInfo.taskId)) {
            exitDesktopTaskFromFullImmersive(taskInfo)
        } else {
            moveDesktopTaskToFullImmersive(taskInfo)
        }
    }

    private fun moveDesktopTaskToFullImmersive(taskInfo: RunningTaskInfo) {
        check(taskInfo.isFreeform) { "Task must already be in freeform" }
        desktopImmersiveController.moveTaskToImmersive(taskInfo)
    }

    private fun exitDesktopTaskFromFullImmersive(taskInfo: RunningTaskInfo) {
        check(taskInfo.isFreeform) { "Task must already be in freeform" }
        desktopImmersiveController.moveTaskToNonImmersive(taskInfo)
    }

    /**
     * Quick-resizes a desktop task, toggling between a fullscreen state (represented by the stable
     * bounds) and a free floating state (either the last saved bounds if available or the default
     * bounds otherwise).
     */
    fun toggleDesktopTaskSize(
        taskInfo: RunningTaskInfo,
        resizeTrigger: ResizeTrigger,
        motionEvent: MotionEvent?,
    ) {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return

        val stableBounds = Rect().apply { displayLayout.getStableBounds(this) }
        val currentTaskBounds = taskInfo.configuration.windowConfiguration.bounds
        val destinationBounds = Rect()

        val isMaximized = isTaskMaximized(taskInfo, stableBounds)
        // If the task is currently maximized, we will toggle it not to be and vice versa. This is
        // helpful to eliminate the current task from logic to calculate taskbar corner rounding.
        val willMaximize = !isMaximized
        if (isMaximized) {
            // The desktop task is at the maximized width and/or height of the stable bounds.
            // If the task's pre-maximize stable bounds were saved, toggle the task to those bounds.
            // Otherwise, toggle to the default bounds.
            val taskBoundsBeforeMaximize =
                taskRepository.removeBoundsBeforeMaximize(taskInfo.taskId)
            if (taskBoundsBeforeMaximize != null) {
                destinationBounds.set(taskBoundsBeforeMaximize)
            } else {
                if (ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS.isTrue()) {
                    destinationBounds.set(calculateInitialBounds(displayLayout, taskInfo))
                } else {
                    destinationBounds.set(calculateDefaultDesktopTaskBounds(displayLayout))
                }
            }
        } else {
            // Save current bounds so that task can be restored back to original bounds if necessary
            // and toggle to the stable bounds.
            desktopTilingDecorViewModel.removeTaskIfTiled(taskInfo.displayId, taskInfo.taskId)
            taskRepository.saveBoundsBeforeMaximize(taskInfo.taskId, currentTaskBounds)

            destinationBounds.set(calculateMaximizeBounds(displayLayout, taskInfo))
        }


        val shouldRestoreToSnap =
            isMaximized && isTaskSnappedToHalfScreen(taskInfo, destinationBounds)

        logD("willMaximize = %s", willMaximize)
        logD("shouldRestoreToSnap = %s", shouldRestoreToSnap)

        val doesAnyTaskRequireTaskbarRounding = willMaximize || shouldRestoreToSnap ||
                doesAnyTaskRequireTaskbarRounding(taskInfo.displayId, taskInfo.taskId)

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding)
        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
        desktopModeEventLogger.logTaskResizingEnded(
            resizeTrigger, motionEvent, taskInfo, destinationBounds.height(),
            destinationBounds.width(), displayController
        )
        toggleResizeDesktopTaskTransitionHandler.startTransition(wct)
    }

    private fun getMaximizeBounds(taskInfo: RunningTaskInfo, stableBounds: Rect): Rect {
        if (taskInfo.isResizeable) {
            // if resizable then expand to entire stable bounds (full display minus insets)
            return Rect(stableBounds)
        } else {
            // if non-resizable then calculate max bounds according to aspect ratio
            val activityAspectRatio = calculateAspectRatio(taskInfo)
            val newSize = maximizeSizeGivenAspectRatio(taskInfo,
                Size(stableBounds.width(), stableBounds.height()), activityAspectRatio)
            return centerInArea(
                newSize, stableBounds, stableBounds.left, stableBounds.top)
        }
    }

    private fun isTaskMaximized(
        taskInfo: RunningTaskInfo,
        stableBounds: Rect
    ): Boolean {
        val currentTaskBounds = taskInfo.configuration.windowConfiguration.bounds

        return if (taskInfo.isResizeable) {
            isTaskBoundsEqual(currentTaskBounds, stableBounds)
        } else {
            isTaskWidthOrHeightEqual(currentTaskBounds, stableBounds)
        }
    }

    private fun isMaximizedToStableBoundsEdges(
        taskInfo: RunningTaskInfo,
        stableBounds: Rect
    ): Boolean {
        val currentTaskBounds = taskInfo.configuration.windowConfiguration.bounds
        return isTaskBoundsEqual(currentTaskBounds, stableBounds)
    }

    /** Returns if current task bound is snapped to half screen */
    private fun isTaskSnappedToHalfScreen(
        taskInfo: RunningTaskInfo,
        taskBounds: Rect = taskInfo.configuration.windowConfiguration.bounds
    ): Boolean =
        getSnapBounds(taskInfo, SnapPosition.LEFT) == taskBounds ||
                getSnapBounds(taskInfo, SnapPosition.RIGHT) == taskBounds

    @VisibleForTesting
    fun doesAnyTaskRequireTaskbarRounding(
        displayId: Int,
        excludeTaskId: Int? = null,
    ): Boolean {
        val doesAnyTaskRequireTaskbarRounding =
            taskRepository.getExpandedTasksOrdered(displayId)
                // exclude current task since maximize/restore transition has not taken place yet.
                .filterNot { taskId -> taskId == excludeTaskId }
                .any { taskId ->
                    val taskInfo = shellTaskOrganizer.getRunningTaskInfo(taskId) ?: return false
                    val displayLayout = displayController.getDisplayLayout(taskInfo.displayId)
                    val stableBounds = Rect().apply { displayLayout?.getStableBounds(this) }
                    logD("taskInfo = %s", taskInfo)
                    logD(
                        "isTaskSnappedToHalfScreen(taskInfo) = %s",
                        isTaskSnappedToHalfScreen(taskInfo)
                    )
                    logD(
                        "isMaximizedToStableBoundsEdges(taskInfo, stableBounds) = %s",
                        isMaximizedToStableBoundsEdges(taskInfo, stableBounds)
                    )
                    isTaskSnappedToHalfScreen(taskInfo)
                            || isMaximizedToStableBoundsEdges(taskInfo, stableBounds)
                }

        logD("doesAnyTaskRequireTaskbarRounding = %s", doesAnyTaskRequireTaskbarRounding)
        return doesAnyTaskRequireTaskbarRounding
    }

    /**
     * Quick-resize to the right or left half of the stable bounds.
     *
     * @param taskInfo current task that is being snap-resized via dragging or maximize menu button
     * @param taskSurface the leash of the task being dragged
     * @param currentDragBounds current position of the task leash being dragged (or current task
     *                          bounds if being snapped resize via maximize menu button)
     * @param position the portion of the screen (RIGHT or LEFT) we want to snap the task to.
     */
    fun snapToHalfScreen(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
        currentDragBounds: Rect,
        position: SnapPosition,
        resizeTrigger: ResizeTrigger,
        motionEvent: MotionEvent?,
        desktopWindowDecoration: DesktopModeWindowDecoration,
    ) {
        if (DesktopModeFlags.ENABLE_TILE_RESIZING.isTrue()) {
            val isTiled = desktopTilingDecorViewModel.snapToHalfScreen(
                taskInfo,
                desktopWindowDecoration,
                position,
                currentDragBounds,
            )
            if (isTiled) {
                taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(true)
            }
            return
        }
        val destinationBounds = getSnapBounds(taskInfo, position)
        desktopModeEventLogger.logTaskResizingEnded(
            resizeTrigger,
            motionEvent,
            taskInfo,
            destinationBounds.height(),
            destinationBounds.width(),
            displayController,
        )
        if (destinationBounds == taskInfo.configuration.windowConfiguration.bounds) {
            // Handle the case where we attempt to snap resize when already snap resized: the task
            // position won't need to change but we want to animate the surface going back to the
            // snapped position from the "dragged-to-the-edge" position.
            if (destinationBounds != currentDragBounds) {
                returnToDragStartAnimator.start(
                    taskInfo.taskId,
                    taskSurface,
                    startBounds = currentDragBounds,
                    endBounds = destinationBounds,
                    isResizable = taskInfo.isResizeable,
                )
            }
            return
        }

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(true)
        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)

        toggleResizeDesktopTaskTransitionHandler.startTransition(wct, currentDragBounds)
    }

    @VisibleForTesting
    fun handleSnapResizingTask(
        taskInfo: RunningTaskInfo,
        position: SnapPosition,
        taskSurface: SurfaceControl,
        currentDragBounds: Rect,
        dragStartBounds: Rect,
        motionEvent: MotionEvent,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
    ) {
        releaseVisualIndicator()
        if (!taskInfo.isResizeable && DISABLE_NON_RESIZABLE_APP_SNAP_RESIZE.isTrue()) {
            interactionJankMonitor.begin(
                taskSurface, context, handler, CUJ_DESKTOP_MODE_SNAP_RESIZE, "drag_non_resizable"
            )

            // reposition non-resizable app back to its original position before being dragged
            returnToDragStartAnimator.start(
                taskInfo.taskId,
                taskSurface,
                startBounds = currentDragBounds,
                endBounds = dragStartBounds,
                isResizable = taskInfo.isResizeable,
            )
        } else {
            val resizeTrigger = if (position == SnapPosition.LEFT) {
                ResizeTrigger.DRAG_LEFT
            } else {
                ResizeTrigger.DRAG_RIGHT
            }
            desktopModeEventLogger.logTaskResizingStarted(
                resizeTrigger, motionEvent, taskInfo, displayController
            )
            interactionJankMonitor.begin(
                taskSurface, context, handler, CUJ_DESKTOP_MODE_SNAP_RESIZE, "drag_resizable"
            )
            snapToHalfScreen(
                taskInfo,
                taskSurface,
                currentDragBounds,
                position,
                resizeTrigger,
                motionEvent,
                desktopModeWindowDecoration,
            )
        }
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
    ): Int? = bringDesktopAppsToFront(displayId, wct, newTaskIdInFront)

    private fun bringDesktopAppsToFront(
        displayId: Int,
        wct: WindowContainerTransaction,
        newTaskIdInFront: Int? = null
    ): Int? {
        logV("bringDesktopAppsToFront, newTaskId=%d", newTaskIdInFront)
        // Move home to front, ensures that we go back home when all desktop windows are closed
        moveHomeTask(wct, toTop = true)

        // Currently, we only handle the desktop on the default display really.
        if (displayId == DEFAULT_DISPLAY
            && ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
            // Add translucent wallpaper activity to show the wallpaper underneath
            addWallpaperActivity(wct)
        }

        val expandedTasksOrderedFrontToBack =
            taskRepository.getExpandedTasksOrdered(displayId)
        // If we're adding a new Task we might need to minimize an old one
        // TODO(b/365725441): Handle non running task minimization
        val taskIdToMinimize: Int? =
            if (newTaskIdInFront != null && desktopTasksLimiter.isPresent) {
                desktopTasksLimiter.get()
                    .getTaskIdToMinimize(
                        expandedTasksOrderedFrontToBack,
                        newTaskIdInFront
                    )
            } else {
                null
            }

        expandedTasksOrderedFrontToBack
            // If there is a Task to minimize, let it stay behind the Home Task
            .filter { taskId -> taskId != taskIdToMinimize }
            .reversed() // Start from the back so the front task is brought forward last
            .forEach { taskId ->
                val runningTaskInfo = shellTaskOrganizer.getRunningTaskInfo(taskId)
                if (runningTaskInfo != null) {
                    // Task is already running, reorder it to the front
                    wct.reorder(runningTaskInfo.token, /* onTop= */ true)
                } else if (Flags.enableDesktopWindowingPersistence()) {
                    // Task is not running, start it
                    wct.startTask(
                        taskId,
                        ActivityOptions.makeBasic().apply {
                            launchWindowingMode = WINDOWING_MODE_FREEFORM
                        }.toBundle(),
                    )
                }
            }

        taskbarDesktopTaskListener?.
            onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding(displayId))

        return taskIdToMinimize
    }

    private fun moveHomeTask(wct: WindowContainerTransaction, toTop: Boolean) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), /* onTop= */ toTop) }
    }

    private fun addWallpaperActivity(wct: WindowContainerTransaction) {
        logV("addWallpaperActivity")
        val intent = Intent(context, DesktopWallpaperActivity::class.java)
        val options =
            ActivityOptions.makeBasic().apply {
                launchWindowingMode = WINDOWING_MODE_FULLSCREEN
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
        wct.sendPendingIntent(pendingIntent, intent, options.toBundle())
    }

    private fun removeWallpaperActivity(wct: WindowContainerTransaction) {
        taskRepository.wallpaperActivityToken?.let { token ->
            logV("removeWallpaperActivity")
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
        logV("handleRequest request=%s", request)
        // Check if we should skip handling this transition
        var reason = ""
        val triggerTask = request.triggerTask
        var shouldHandleMidRecentsFreeformLaunch =
            recentsAnimationRunning && isFreeformRelaunch(triggerTask, request)
        val isDragAndDropFullscreenTransition = taskContainsDragAndDropCookie(triggerTask)
        val shouldHandleRequest =
            when {
                // Handle freeform relaunch during recents animation
                shouldHandleMidRecentsFreeformLaunch -> true
                recentsAnimationRunning -> {
                    reason = "recents animation is running"
                    false
                }
                // Don't handle request if this was a tear to fullscreen transition.
                // handleFullscreenTaskLaunch moves fullscreen intents to freeform;
                // this is an exception to the rule
                isDragAndDropFullscreenTransition -> {
                    dragAndDropFullscreenCookie = null
                    false
                }
                // Handle task closing for the last window if wallpaper is available
                shouldHandleTaskClosing(request) -> true
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
                !triggerTask.isFullscreen && !triggerTask.isFreeform -> {
                    reason = "windowingMode not handled (${triggerTask.windowingMode})"
                    false
                }
                // Otherwise process it
                else -> true
            }

        if (!shouldHandleRequest) {
            logV("skipping handleRequest reason=%s", reason)
            return null
        }

        val result =
            triggerTask?.let { task ->
                when {
                    // Check if freeform task launch during recents should be handled
                    shouldHandleMidRecentsFreeformLaunch -> handleMidRecentsFreeformTaskLaunch(task)
                    // Check if the closing task needs to be handled
                    TransitionUtil.isClosingType(request.type) -> handleTaskClosing(task)
                    // Check if the top task shouldn't be allowed to enter desktop mode
                    isIncompatibleTask(task) -> handleIncompatibleTaskLaunch(task)
                    // Check if fullscreen task should be updated
                    task.isFullscreen -> handleFullscreenTaskLaunch(task, transition)
                    // Check if freeform task should be updated
                    task.isFreeform -> handleFreeformTaskLaunch(task, transition)
                    else -> {
                        null
                    }
                }
            }
        logV("handleRequest result=%s", result)
        return result
    }

    /** Whether the given [change] in the [transition] is a known desktop change. */
    fun isDesktopChange(
        transition: IBinder,
        change: TransitionInfo.Change,
    ): Boolean {
        // Only the immersive controller is currently involved in mixed transitions.
        return Flags.enableFullyImmersiveInDesktop()
                && desktopImmersiveController.isImmersiveChange(transition, change)
    }

    /**
     * Whether the given transition [info] will potentially include a desktop change, in which
     * case the transition should be treated as mixed so that the change is in part animated by
     * one of the desktop transition handlers.
     */
    fun shouldPlayDesktopAnimation(info: TransitionRequestInfo): Boolean {
        // Only immersive mixed transition are currently supported.
        if (!Flags.enableFullyImmersiveInDesktop()) return false
        val triggerTask = info.triggerTask ?: return false
        if (!isDesktopModeShowing(triggerTask.displayId)) {
            return false
        }
        if (!TransitionUtil.isOpeningType(info.type)) {
            return false
        }
        taskRepository.getTaskInFullImmersiveState(displayId = triggerTask.displayId)
            ?: return false
        return when {
            triggerTask.isFullscreen -> {
                // Trigger fullscreen task will enter desktop, so any existing immersive task
                // should exit.
                shouldFullscreenTaskLaunchSwitchToDesktop(triggerTask)
            }
            triggerTask.isFreeform -> {
                // Trigger freeform task will enter desktop, so any existing immersive task should
                // exit.
                !shouldFreeformTaskLaunchSwitchToFullscreen(triggerTask)
            }
            else -> false
        }
    }

    /** Animate a desktop change found in a mixed transitions. */
    fun animateDesktopChange(
        transition: IBinder,
        change: Change,
        startTransaction: Transaction,
        finishTransaction: Transaction,
        finishCallback: TransitionFinishCallback,
    ) {
        if (!desktopImmersiveController.isImmersiveChange(transition, change)) {
            throw IllegalStateException("Only immersive changes support desktop mixed transitions")
        }
        desktopImmersiveController.animateResizeChange(
            change,
            startTransaction,
            finishTransaction,
            finishCallback
        )
    }

    private fun taskContainsDragAndDropCookie(taskInfo: RunningTaskInfo?) =
        taskInfo?.launchCookies?.any { it == dragAndDropFullscreenCookie } ?: false

    /**
     * Applies the proper surface states (rounded corners) to tasks when desktop mode is active.
     * This is intended to be used when desktop mode is part of another animation but isn't, itself,
     * animating.
     */
    fun syncSurfaceState(info: TransitionInfo, finishTransaction: SurfaceControl.Transaction) {
        // Add rounded corners to freeform windows
        if (!DesktopModeStatus.useRoundedCorners()) {
            return
        }
        val cornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context)
        info.changes
            .filter { it.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM }
            .forEach { finishTransaction.setCornerRadius(it.leash, cornerRadius) }
    }

    /** Returns whether an existing desktop task is being relaunched in freeform or not. */
    private fun isFreeformRelaunch(triggerTask: RunningTaskInfo?, request: TransitionRequestInfo) =
        (triggerTask != null && triggerTask.windowingMode == WINDOWING_MODE_FREEFORM
                && TransitionUtil.isOpeningType(request.type)
                && taskRepository.isActiveTask(triggerTask.taskId))

    private fun isIncompatibleTask(task: TaskInfo) =
        DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue()
                && isTopActivityExemptFromDesktopWindowing(context, task)

    private fun shouldHandleTaskClosing(request: TransitionRequestInfo): Boolean {
        return ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue() &&
            TransitionUtil.isClosingType(request.type) &&
            request.triggerTask != null
    }

    /** Open an existing instance of an app. */
    fun openInstance(
        callingTask: RunningTaskInfo,
        requestedTaskId: Int
    ) {
        val wct = WindowContainerTransaction()
        val options = createNewWindowOptions(callingTask)
        if (options.launchWindowingMode == WINDOWING_MODE_FREEFORM) {
            wct.startTask(requestedTaskId, options.toBundle())
            val taskIdToMinimize = bringDesktopAppsToFrontBeforeShowingNewTask(
                callingTask.displayId, wct, requestedTaskId)
            val runOnTransit = desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                displayId = callingTask.displayId,
                excludeTaskId = requestedTaskId,
            )
            val transition = transitions.startTransition(TRANSIT_OPEN, wct, null)
            taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
            runOnTransit?.invoke(transition)
        } else {
            val splitPosition = splitScreenController.determineNewInstancePosition(callingTask)
            splitScreenController.startTask(requestedTaskId, splitPosition,
                options.toBundle(), null /* hideTaskToken */)
        }
    }

    /** Create an Intent to open a new window of a task. */
    fun openNewWindow(
        callingTaskInfo: RunningTaskInfo
    ) {
        // TODO(b/337915660): Add a transition handler for these; animations
        //  need updates in some cases.
        val baseActivity = callingTaskInfo.baseActivity ?: return
        val fillIn: Intent = context.packageManager
            .getLaunchIntentForPackage(
                baseActivity.packageName
            ) ?: return
        fillIn
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val launchIntent = PendingIntent.getActivity(
            context,
            /* requestCode= */ 0,
            fillIn,
            PendingIntent.FLAG_IMMUTABLE
        )
        val options = createNewWindowOptions(callingTaskInfo)
        when (options.launchWindowingMode) {
            WINDOWING_MODE_MULTI_WINDOW -> {
                val splitPosition = splitScreenController
                    .determineNewInstancePosition(callingTaskInfo)
                splitScreenController.startIntent(
                    launchIntent, context.userId, fillIn, splitPosition,
                    options.toBundle(), null /* hideTaskToken */,
                    true /* forceLaunchNewTask */
                )
            }
            WINDOWING_MODE_FREEFORM -> {
                // TODO(b/336289597): This currently does not respect the desktop window limit.
                val wct = WindowContainerTransaction()
                wct.sendPendingIntent(launchIntent, fillIn, options.toBundle())
                transitions.startTransition(TRANSIT_OPEN, wct, null)
            }
        }
    }

    private fun createNewWindowOptions(callingTask: RunningTaskInfo): ActivityOptions {
        val newTaskWindowingMode = when {
            callingTask.isFreeform -> {
                WINDOWING_MODE_FREEFORM
            }
            callingTask.isFullscreen || callingTask.isMultiWindow -> {
                WINDOWING_MODE_MULTI_WINDOW
            }
            else -> {
                error("Invalid windowing mode: ${callingTask.windowingMode}")
            }
        }
        val bounds = when (newTaskWindowingMode) {
            WINDOWING_MODE_FREEFORM -> {
                displayController.getDisplayLayout(callingTask.displayId)
                    ?.let { getInitialBounds(it, callingTask, callingTask.displayId) }
            }
            WINDOWING_MODE_MULTI_WINDOW -> {
                Rect()
            }
            else -> {
                error("Invalid windowing mode: $newTaskWindowingMode")
            }
        }
        return ActivityOptions.makeBasic().apply {
            launchWindowingMode = newTaskWindowingMode
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            launchBounds = bounds
        }
    }

    /**
     * Handles the case where a freeform task is launched from recents.
     *
     * This is a special case where we want to launch the task in fullscreen instead of freeform.
     */
    private fun handleMidRecentsFreeformTaskLaunch(
        task: RunningTaskInfo
    ): WindowContainerTransaction? {
        logV("DesktopTasksController: handleMidRecentsFreeformTaskLaunch")
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task)
        wct.reorder(task.token, true)
        return wct
    }

    private fun handleFreeformTaskLaunch(
        task: RunningTaskInfo,
        transition: IBinder
    ): WindowContainerTransaction? {
        logV("handleFreeformTaskLaunch")
        if (keyguardManager.isKeyguardLocked) {
            // Do NOT handle freeform task launch when locked.
            // It will be launched in fullscreen windowing mode (Details: b/160925539)
            logV("skip keyguard is locked")
            return null
        }
        val wct = WindowContainerTransaction()
        if (shouldFreeformTaskLaunchSwitchToFullscreen(task)) {
            logD("Bring desktop tasks to front on transition=taskId=%d", task.taskId)
            if (taskRepository.isActiveTask(task.taskId) && !forceEnterDesktop(task.displayId)) {
                // We are outside of desktop mode and already existing desktop task is being
                // launched. We should make this task go to fullscreen instead of freeform. Note
                // that this means any re-launch of a freeform window outside of desktop will be in
                // fullscreen as long as default-desktop flag is disabled.
                addMoveToFullscreenChanges(wct, task)
                return wct
            }
            bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
            wct.reorder(task.token, true)
            return wct
        }
        // TODO(b/365723620): Handle non running tasks that were launched after reboot.
        // If task is already visible, it must have been handled already and added to desktop mode.
        // Cascade task only if it's not visible yet.
        if (DesktopModeFlags.ENABLE_CASCADING_WINDOWS.isTrue()
                && !taskRepository.isVisibleTask(task.taskId)) {
            val displayLayout = displayController.getDisplayLayout(task.displayId)
            if (displayLayout != null) {
                val initialBounds = Rect(task.configuration.windowConfiguration.bounds)
                cascadeWindow(initialBounds, displayLayout, task.displayId)
                wct.setBounds(task.token, initialBounds)
            }
        }
        if (useDesktopOverrideDensity()) {
            wct.setDensityDpi(task.token, DESKTOP_DENSITY_OVERRIDE)
        }
        // Desktop Mode is showing and we're launching a new Task:
        // 1) Exit immersive if needed.
        desktopImmersiveController.exitImmersiveIfApplicable(transition, wct, task.displayId)
        // 2) minimize a Task if needed.
        val taskIdToMinimize = addAndGetMinimizeChanges(task.displayId, wct, task.taskId)
        if (taskIdToMinimize != null) {
            addPendingMinimizeTransition(transition, taskIdToMinimize)
            return wct
        }
        if (!wct.isEmpty) {
            desktopTilingDecorViewModel.removeTaskIfTiled(task.displayId, task.taskId)
            return wct
        }
        return null
    }

    private fun handleFullscreenTaskLaunch(
        task: RunningTaskInfo,
        transition: IBinder
    ): WindowContainerTransaction? {
        logV("handleFullscreenTaskLaunch")
        if (shouldFullscreenTaskLaunchSwitchToDesktop(task)) {
            logD("Switch fullscreen task to freeform on transition: taskId=%d", task.taskId)
            return WindowContainerTransaction().also { wct ->
                addMoveToDesktopChanges(wct, task)
                // In some launches home task is moved behind new task being launched. Make sure
                // that's not the case for launches in desktop.
                if (task.baseIntent.flags.and(Intent.FLAG_ACTIVITY_TASK_ON_HOME) != 0) {
                    bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
                    wct.reorder(task.token, true)
                }

                // Desktop Mode is already showing and we're launching a new Task - we might need to
                // minimize another Task.
                val taskIdToMinimize = addAndGetMinimizeChanges(task.displayId, wct, task.taskId)
                taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
                desktopImmersiveController.exitImmersiveIfApplicable(
                    transition, wct, task.displayId
                )
            }
        }
        return null
    }

    private fun shouldFreeformTaskLaunchSwitchToFullscreen(task: RunningTaskInfo): Boolean =
        !isDesktopModeShowing(task.displayId)

    private fun shouldFullscreenTaskLaunchSwitchToDesktop(task: RunningTaskInfo): Boolean =
        isDesktopModeShowing(task.displayId) || forceEnterDesktop(task.displayId)

    /**
     * If a task is not compatible with desktop mode freeform, it should always be launched in
     * fullscreen.
     */
    private fun handleIncompatibleTaskLaunch(task: RunningTaskInfo): WindowContainerTransaction? {
        // Already fullscreen, no-op.
        if (task.isFullscreen) return null
        return WindowContainerTransaction().also { wct -> addMoveToFullscreenChanges(wct, task) }
    }

    /** Handle task closing by removing wallpaper activity if it's the last active task */
    private fun handleTaskClosing(task: RunningTaskInfo): WindowContainerTransaction? {
        logV("handleTaskClosing")
        if (!isDesktopModeShowing(task.displayId))
            return null

        val wct = WindowContainerTransaction()
        if (taskRepository.isOnlyVisibleNonClosingTask(task.taskId)
            && taskRepository.wallpaperActivityToken != null
        ) {
            // Remove wallpaper activity when the last active task is removed
            removeWallpaperActivity(wct)
        }

        if (!DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) {
            taskRepository.addClosingTask(task.displayId, task.taskId)
            desktopTilingDecorViewModel.removeTaskIfTiled(task.displayId, task.taskId)
        }

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(
                task.displayId,
                task.taskId
            )
        )
        return if (wct.isEmpty) null else wct
    }

    /**
     * Apply all changes required when task is first added to desktop. Uses the task's current
     * display by default to apply initial bounds and placement relative to the display.
     * Use a different [displayId] if the task should be moved to a different display.
     */
    @VisibleForTesting
    fun addMoveToDesktopChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo,
        displayId: Int = taskInfo.displayId,
    ) {
        val displayLayout = displayController.getDisplayLayout(displayId) ?: return
        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)!!
        val tdaWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        val targetWindowingMode =
            if (tdaWindowingMode == WINDOWING_MODE_FREEFORM) {
                // Display windowing is freeform, set to undefined and inherit it
                WINDOWING_MODE_UNDEFINED
            } else {
                WINDOWING_MODE_FREEFORM
            }
        val initialBounds = getInitialBounds(displayLayout, taskInfo, displayId)

        if (canChangeTaskPosition(taskInfo)) {
            wct.setBounds(taskInfo.token, initialBounds)
        }
        wct.setWindowingMode(taskInfo.token, targetWindowingMode)
        wct.reorder(taskInfo.token, true /* onTop */)
        if (useDesktopOverrideDensity()) {
            wct.setDensityDpi(taskInfo.token, DESKTOP_DENSITY_OVERRIDE)
        }
    }

    private fun getInitialBounds(
        displayLayout: DisplayLayout,
        taskInfo: RunningTaskInfo,
        displayId: Int,
    ): Rect {
        val bounds = if (ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS.isTrue) {
            calculateInitialBounds(displayLayout, taskInfo)
        } else {
            calculateDefaultDesktopTaskBounds(displayLayout)
        }

        if (DesktopModeFlags.ENABLE_CASCADING_WINDOWS.isTrue) {
            cascadeWindow(bounds, displayLayout, displayId)
        }
        return bounds
    }

    private fun addMoveToFullscreenChanges(
        wct: WindowContainerTransaction,
        taskInfo: RunningTaskInfo
    ) {
        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId)!!
        val tdaWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        val targetWindowingMode =
            if (tdaWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                // Display windowing is fullscreen, set to undefined and inherit it
                WINDOWING_MODE_UNDEFINED
            } else {
                WINDOWING_MODE_FULLSCREEN
            }
        wct.setWindowingMode(taskInfo.token, targetWindowingMode)
        wct.setBounds(taskInfo.token, Rect())
        if (useDesktopOverrideDensity()) {
            wct.setDensityDpi(taskInfo.token, getDefaultDensityDpi())
        }
        if (taskRepository.isOnlyVisibleNonClosingTask(taskInfo.taskId)) {
            // Remove wallpaper activity when leaving desktop mode
            removeWallpaperActivity(wct)
        }
    }

    private fun cascadeWindow(bounds: Rect, displayLayout: DisplayLayout, displayId: Int) {
        val stableBounds = Rect()
        displayLayout.getStableBoundsForDesktopMode(stableBounds)

        val activeTasks = taskRepository.getExpandedTasksOrdered(displayId)
        activeTasks.firstOrNull()?.let { activeTask ->
            shellTaskOrganizer.getRunningTaskInfo(activeTask)?.let {
                cascadeWindow(context.resources, stableBounds,
                    it.configuration.windowConfiguration.bounds, bounds)
            }
        }
    }

    /**
     * Adds split screen changes to a transaction. Note that bounds are not reset here due to
     * animation; see {@link onDesktopSplitSelectAnimComplete}
     */
    private fun addMoveToSplitChanges(wct: WindowContainerTransaction, taskInfo: RunningTaskInfo) {
        // This windowing mode is to get the transition animation started; once we complete
        // split select, we will change windowing mode to undefined and inherit from split stage.
        // Going to undefined here causes task to flicker to the top left.
        // Cancelling the split select flow will revert it to fullscreen.
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_MULTI_WINDOW)
        // The task's density may have been overridden in freeform; revert it here as we don't
        // want it overridden in multi-window.
        wct.setDensityDpi(taskInfo.token, getDefaultDensityDpi())
        if (taskRepository.isOnlyVisibleNonClosingTask(taskInfo.taskId)) {
            // Remove wallpaper activity when leaving desktop mode
            removeWallpaperActivity(wct)
        }
    }

    /** Returns the ID of the Task that will be minimized, or null if no task will be minimized. */
    private fun addAndGetMinimizeChanges(
        displayId: Int,
        wct: WindowContainerTransaction,
        newTaskId: Int
    ): Int? {
        if (!desktopTasksLimiter.isPresent) return null
        return desktopTasksLimiter
            .get()
            .addAndGetMinimizeTaskChanges(displayId, wct, newTaskId)
    }

    private fun addPendingMinimizeTransition(
        transition: IBinder,
        taskIdToMinimize: Int,
    ) {
        val taskToMinimize = shellTaskOrganizer.getRunningTaskInfo(taskIdToMinimize) ?: return
        desktopTasksLimiter.ifPresent {
            it.addPendingMinimizeChange(transition, taskToMinimize.displayId, taskToMinimize.taskId)
        }
    }

    fun removeDesktop(displayId: Int) {
        if (!DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) return

        val tasksToRemove = taskRepository.removeDesktop(displayId)
        val wct = WindowContainerTransaction()
        tasksToRemove.forEach {
            val task = shellTaskOrganizer.getRunningTaskInfo(it)
            if (task != null) {
                wct.removeTask(task.token)
            } else {
                recentTasksController?.removeBackgroundTask(it)
            }
        }
        if (!wct.isEmpty) transitions.startTransition(TRANSIT_CLOSE, wct, null)
    }

    /** Enter split by using the focused desktop task in given `displayId`. */
    fun enterSplit(displayId: Int, leftOrTop: Boolean) {
        getFocusedFreeformTask(displayId)?.let { requestSplit(it, leftOrTop) }
    }

    /** Move the focused desktop task in given `displayId` to next display. */
    fun moveFocusedTaskToNextDisplay(displayId: Int) {
        getFocusedFreeformTask(displayId)?.let { moveToNextDisplay(it.taskId) }
    }

    private fun getFocusedFreeformTask(displayId: Int): RunningTaskInfo? {
        return shellTaskOrganizer.getRunningTasks(displayId).find { taskInfo ->
            taskInfo.isFocused && taskInfo.windowingMode == WINDOWING_MODE_FREEFORM
        }
    }

    // TODO(b/364154795): wait for the completion of moveToNextDisplay transition, otherwise it will
    //  pick a wrong task when a user quickly perform other actions with keyboard shortcuts after
    //  moveToNextDisplay.
    private fun getGloballyFocusedFreeformTask(): RunningTaskInfo? =
        shellTaskOrganizer.getRunningTasks().find { taskInfo ->
            taskInfo.windowingMode == WINDOWING_MODE_FREEFORM &&
                    focusTransitionObserver.hasGlobalFocus(taskInfo)
        }

    /**
     * Requests a task be transitioned from desktop to split select. Applies needed windowing
     * changes if this transition is enabled.
     */
    @JvmOverloads
    fun requestSplit(
        taskInfo: RunningTaskInfo,
        leftOrTop: Boolean = false
    ) {
        // If a drag to desktop is in progress, we want to enter split select
        // even if the requesting task is already in split.
        val isDragging = dragToDesktopTransitionHandler.inProgress
        val shouldRequestSplit = taskInfo.isFullscreen || taskInfo.isFreeform || isDragging
        if (shouldRequestSplit) {
            if (isDragging) {
                releaseVisualIndicator()
                val cancelState = if (leftOrTop) {
                    DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_LEFT
                } else {
                    DragToDesktopTransitionHandler.CancelState.CANCEL_SPLIT_RIGHT
                }
                dragToDesktopTransitionHandler.cancelDragToDesktopTransition(cancelState)
            } else {
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
    }

    private fun getDefaultDensityDpi(): Int {
        return context.resources.displayMetrics.densityDpi
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
     * Different sources for x and y coordinates are used due to different needs for each: We want
     * split transitions to be based on input coordinates but fullscreen transition to be based on
     * task edge coordinate.
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
        desktopTilingDecorViewModel.removeTaskIfTiled(taskInfo.displayId, taskInfo.taskId)
        updateVisualIndicator(taskInfo, taskSurface, inputX, taskBounds.top.toFloat(),
            DragStartState.FROM_FREEFORM)
    }

    fun updateVisualIndicator(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl?,
        inputX: Float,
        taskTop: Float,
        dragStartState: DragStartState
    ): DesktopModeVisualIndicator.IndicatorType {
        // If the visual indicator does not exist, create it.
        val indicator =
            visualIndicator
                ?: DesktopModeVisualIndicator(
                    syncQueue,
                    taskInfo,
                    displayController,
                    context,
                    taskSurface,
                    rootTaskDisplayAreaOrganizer,
                    dragStartState
                )
        if (visualIndicator == null) visualIndicator = indicator
        return indicator.updateIndicatorType(PointF(inputX, taskTop))
    }

    /**
     * Perform checks required on drag end. If indicator indicates a windowing mode change, perform
     * that change. Otherwise, ensure bounds are up to date.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface the leash of the task being dragged.
     * @param position position of surface when drag ends.
     * @param inputCoordinate the coordinates of the motion event
     * @param currentDragBounds the current bounds of where the visible task is (might be actual
     *                          task bounds or just task leash)
     * @param validDragArea the bounds of where the task can be dragged within the display.
     * @param dragStartBounds the bounds of the task before starting dragging.
     */
    fun onDragPositioningEnd(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
        position: Point,
        inputCoordinate: PointF,
        currentDragBounds: Rect,
        validDragArea: Rect,
        dragStartBounds: Rect,
        motionEvent: MotionEvent,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
    ) {
        if (taskInfo.configuration.windowConfiguration.windowingMode != WINDOWING_MODE_FREEFORM) {
            return
        }

        val indicator = getVisualIndicator() ?: return
        val indicatorType =
            indicator.updateIndicatorType(
                PointF(inputCoordinate.x, currentDragBounds.top.toFloat()),
            )
        when (indicatorType) {
            IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                moveToFullscreenWithAnimation(
                    taskInfo,
                    position,
                    DesktopModeTransitionSource.TASK_DRAG
                )
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                handleSnapResizingTask(
                    taskInfo,
                    SnapPosition.LEFT,
                    taskSurface,
                    currentDragBounds,
                    dragStartBounds,
                    motionEvent,
                    desktopModeWindowDecoration,
                )
            }
            IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                handleSnapResizingTask(
                    taskInfo,
                    SnapPosition.RIGHT,
                    taskSurface,
                    currentDragBounds,
                    dragStartBounds,
                    motionEvent,
                    desktopModeWindowDecoration,
                )
            }
            IndicatorType.NO_INDICATOR -> {
                // If task bounds are outside valid drag area, snap them inward
                DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(
                    currentDragBounds,
                    validDragArea
                )

                if (currentDragBounds == dragStartBounds) return

                // Update task bounds so that the task position will match the position of its leash
                val wct = WindowContainerTransaction()
                wct.setBounds(taskInfo.token, currentDragBounds)
                transitions.startTransition(TRANSIT_CHANGE, wct, null)

                releaseVisualIndicator()
            }
            IndicatorType.TO_DESKTOP_INDICATOR -> {
                throw IllegalArgumentException(
                    "Should not be receiving TO_DESKTOP_INDICATOR for " + "a freeform task."
                )
            }
        }
        // A freeform drag-move ended, remove the indicator immediately.
        releaseVisualIndicator()
        taskbarDesktopTaskListener
            ?.onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding(taskInfo.displayId))
    }

    /**
     * Perform checks required when drag ends under status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param y height of drag, to be checked against status bar height.
     * @return the [IndicatorType] used for the resulting transition
     */
    fun onDragPositioningEndThroughStatusBar(
        inputCoordinates: PointF,
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
    ): IndicatorType {
        // End the drag_hold CUJ interaction.
        interactionJankMonitor.end(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD)
        val indicator = getVisualIndicator() ?: return IndicatorType.NO_INDICATOR
        val indicatorType = indicator.updateIndicatorType(inputCoordinates)
        when (indicatorType) {
            IndicatorType.TO_DESKTOP_INDICATOR -> {
                // Start a new jank interaction for the drag release to desktop window animation.
                interactionJankMonitor.begin(taskSurface, context, handler,
                    CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE, "to_desktop")
                finalizeDragToDesktop(taskInfo)
            }
            IndicatorType.NO_INDICATOR,
            IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                cancelDragToDesktop(taskInfo)
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                requestSplit(taskInfo, leftOrTop = true)
            }
            IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                requestSplit(taskInfo, leftOrTop = false)
            }
        }
        return indicatorType
    }

    /** Update the exclusion region for a specified task */
    fun onExclusionRegionChanged(taskId: Int, exclusionRegion: Region) {
        taskRepository.updateTaskExclusionRegions(taskId, exclusionRegion)
    }

    /** Remove a previously tracked exclusion region for a specified task. */
    fun removeExclusionRegionForTask(taskId: Int) {
        taskRepository.removeExclusionRegion(taskId)
    }

    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun addVisibleTasksListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
        taskRepository.addVisibleTasksListener(listener, callbackExecutor)
    }

    /**
     * Adds a listener to track changes to desktop task gesture exclusion regions
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun setTaskRegionListener(listener: Consumer<Region>, callbackExecutor: Executor) {
        taskRepository.setExclusionRegionListener(listener, callbackExecutor)
    }

    // TODO(b/358114479): Move this implementation into a separate class.
    override fun onUnhandledDrag(
        launchIntent: PendingIntent,
        dragEvent: DragEvent,
        onFinishCallback: Consumer<Boolean>
    ): Boolean {
        // TODO(b/320797628): Pass through which display we are dropping onto
        if (!isDesktopModeShowing(DEFAULT_DISPLAY)) {
            // Not currently in desktop mode, ignore the drop
            return false
        }
        val launchComponent = getComponent(launchIntent)
        if (!multiInstanceHelper.supportsMultiInstanceSplit(launchComponent)) {
            // TODO(b/320797628): Should only return early if there is an existing running task, and
            //                    notify the user as well. But for now, just ignore the drop.
            logV("Dropped intent does not support multi-instance")
            return false
        }
        val taskInfo = getFocusedFreeformTask(DEFAULT_DISPLAY) ?: return false
        // TODO(b/358114479): Update drag and drop handling to give us visibility into when another
        //  window will accept a drag event. This way, we can hide the indicator when we won't
        //  be handling the transition here, allowing us to display the indicator accurately.
        //  For now, we create the indicator only on drag end and immediately dispose it.
        val indicatorType = updateVisualIndicator(taskInfo, dragEvent.dragSurface,
            dragEvent.x, dragEvent.y,
            DragStartState.DRAGGED_INTENT)
        releaseVisualIndicator()
        val windowingMode = when (indicatorType) {
            IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                WINDOWING_MODE_FULLSCREEN
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR,
            IndicatorType.TO_SPLIT_RIGHT_INDICATOR,
            IndicatorType.TO_DESKTOP_INDICATOR
            -> {
                WINDOWING_MODE_FREEFORM
            }
            else -> error("Invalid indicator type: $indicatorType")
        }
        val displayLayout = displayController.getDisplayLayout(DEFAULT_DISPLAY) ?: return false
        val newWindowBounds = Rect()
        when (indicatorType) {
            IndicatorType.TO_DESKTOP_INDICATOR -> {
                // Use default bounds, but with the top-center at the drop point.
                newWindowBounds.set(calculateDefaultDesktopTaskBounds(displayLayout))
                newWindowBounds.offsetTo(
                    dragEvent.x.toInt() - (newWindowBounds.width() / 2),
                    dragEvent.y.toInt()
                )
            }
            IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                newWindowBounds.set(getSnapBounds(taskInfo, SnapPosition.RIGHT))
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                newWindowBounds.set(getSnapBounds(taskInfo, SnapPosition.LEFT))
            }
            else -> {
                // Use empty bounds for the fullscreen case.
            }
        }
        // Start a new transition to launch the app
        val opts =
            ActivityOptions.makeBasic().apply {
                launchWindowingMode = windowingMode
                launchBounds = newWindowBounds
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                pendingIntentLaunchFlags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            dragAndDropFullscreenCookie = Binder()
            opts.launchCookie = dragAndDropFullscreenCookie
        }
        val wct = WindowContainerTransaction()
        wct.sendPendingIntent(launchIntent, null, opts.toBundle())
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            desktopModeDragAndDropTransitionHandler.handleDropEvent(wct)
        } else {
            transitions.startTransition(TRANSIT_OPEN, wct, null)
        }

        // Report that this is handled by the listener
        onFinishCallback.accept(true)

        // We've assumed responsibility of cleaning up the drag surface, so do that now
        // TODO(b/320797628): Do an actual animation here for the drag surface
        val t = SurfaceControl.Transaction()
        t.remove(dragEvent.dragSurface)
        t.apply()
        return true
    }

    // TODO(b/366397912): Support full multi-user mode in Windowing.
    override fun onUserChanged(newUserId: Int, userContext: Context) {
        userId = newUserId
        desktopTilingDecorViewModel.onUserChange()
    }

    /** Called when a task's info changes. */
    fun onTaskInfoChanged(taskInfo: RunningTaskInfo) {
        if (!Flags.enableFullyImmersiveInDesktop()) return
        val inImmersive = taskRepository.isTaskInFullImmersiveState(taskInfo.taskId)
        val requestingImmersive = taskInfo.requestingImmersive
        if (inImmersive && !requestingImmersive) {
            // Exit immersive if the app is no longer requesting it.
            exitDesktopTaskFromFullImmersive(taskInfo)
        }
    }

    private fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopTasksController")
        DesktopModeStatus.dump(pw, innerPrefix, context)
        taskRepository.dump(pw, innerPrefix)
    }

    override fun handleKeyGestureEvent(
        event: KeyGestureEvent,
        focusedToken: IBinder?
    ): Boolean {
        if (!isKeyGestureSupported(event.keyGestureType)) return false
        when (event.keyGestureType) {
            KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY -> {
                if (event.keycodes.contains(KeyEvent.KEYCODE_D) &&
                    event.hasModifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON)) {
                    logV("Key gesture MOVE_TO_NEXT_DISPLAY is handled")
                    getGloballyFocusedFreeformTask()?.let { moveToNextDisplay(it.taskId) }
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    override fun isKeyGestureSupported(gestureType: Int): Boolean = when (gestureType) {
        KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY
            -> enableMoveToNextDisplayShortcut()
        else -> false
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

        override fun moveFocusedTaskToDesktop(
            displayId: Int,
            transitionSource: DesktopModeTransitionSource
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.moveFocusedTaskToDesktop(displayId, transitionSource)
            }
        }

        override fun moveFocusedTaskToFullscreen(
            displayId: Int,
            transitionSource: DesktopModeTransitionSource
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.enterFullscreen(displayId, transitionSource)
            }
        }

        override fun moveFocusedTaskToStageSplit(displayId: Int, leftOrTop: Boolean) {
            mainExecutor.execute { this@DesktopTasksController.enterSplit(displayId, leftOrTop) }
        }
    }

    /** The interface for calls from outside the host process. */
    @BinderThread
    private class IDesktopModeImpl(private var controller: DesktopTasksController?) :
        IDesktopMode.Stub(), ExternalInterfaceBinder {

        private lateinit var remoteListener:
            SingleInstanceRemoteListener<DesktopTasksController, IDesktopTaskListener>

        private val listener: VisibleTasksListener =
            object : VisibleTasksListener {
                override fun onTasksVisibilityChanged(displayId: Int, visibleTasksCount: Int) {
                    ProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onVisibilityChanged display=%d visible=%d",
                        displayId,
                        visibleTasksCount
                    )
                    remoteListener.call { l ->
                        l.onTasksVisibilityChanged(displayId, visibleTasksCount)
                    }
                }
            }

        private val mTaskbarDesktopTaskListener: TaskbarDesktopTaskListener =
                object : TaskbarDesktopTaskListener {
                    override fun onTaskbarCornerRoundingUpdate(
                        hasTasksRequiringTaskbarRounding: Boolean) {
                        ProtoLog.v(
                                WM_SHELL_DESKTOP_MODE,
                                "IDesktopModeImpl: onTaskbarCornerRoundingUpdate " +
                                        "doesAnyTaskRequireTaskbarRounding=%s",
                                hasTasksRequiringTaskbarRounding
                        )

                        remoteListener.call { l ->
                            l.onTaskbarCornerRoundingUpdate(hasTasksRequiringTaskbarRounding)
                        }
                    }
                }

        init {
            remoteListener =
                SingleInstanceRemoteListener<DesktopTasksController, IDesktopTaskListener>(
                    controller,
                    { c ->
                        run {
                            c.taskRepository.addVisibleTasksListener(listener, c.mainExecutor)
                            c.taskbarDesktopTaskListener = mTaskbarDesktopTaskListener
                        }
                    },
                    { c ->
                        run {
                            c.taskRepository.removeVisibleTasksListener(listener)
                            c.taskbarDesktopTaskListener = null
                        }
                    }
                )
        }

        /** Invalidates this instance, preventing future calls from updating the controller. */
        override fun invalidate() {
            remoteListener.unregister()
            controller = null
        }

        override fun showDesktopApps(displayId: Int, remoteTransition: RemoteTransition?) {
            executeRemoteCallWithTaskPermission(controller, "showDesktopApps") { c ->
                c.showDesktopApps(displayId, remoteTransition)
            }
        }

        override fun showDesktopApp(taskId: Int, remoteTransition: RemoteTransition?) {
            executeRemoteCallWithTaskPermission(controller, "showDesktopApp") { c ->
                c.moveTaskToFront(taskId, remoteTransition)
            }
        }

        override fun stashDesktopApps(displayId: Int) {
            ProtoLog.w(WM_SHELL_DESKTOP_MODE, "IDesktopModeImpl: stashDesktopApps is deprecated")
        }

        override fun hideStashedDesktopApps(displayId: Int) {
            ProtoLog.w(WM_SHELL_DESKTOP_MODE,
                "IDesktopModeImpl: hideStashedDesktopApps is deprecated")
        }

        override fun getVisibleTaskCount(displayId: Int): Int {
            val result = IntArray(1)
            executeRemoteCallWithTaskPermission(
                controller,
                "visibleTaskCount",
                { controller -> result[0] = controller.visibleTaskCount(displayId) },
                true /* blocking */
            )
            return result[0]
        }

        override fun onDesktopSplitSelectAnimComplete(taskInfo: RunningTaskInfo) {
            executeRemoteCallWithTaskPermission(
                controller,
                "onDesktopSplitSelectAnimComplete"
            ) { c ->
                c.onDesktopSplitSelectAnimComplete(taskInfo)
            }
        }

        override fun setTaskListener(listener: IDesktopTaskListener?) {
            ProtoLog.v(WM_SHELL_DESKTOP_MODE, "IDesktopModeImpl: set task listener=%s", listener)
            executeRemoteCallWithTaskPermission(controller, "setTaskListener") { _ ->
                listener?.let { remoteListener.register(it) } ?: remoteListener.unregister()
            }
        }

        override fun moveToDesktop(taskId: Int, transitionSource: DesktopModeTransitionSource) {
            executeRemoteCallWithTaskPermission(controller, "moveTaskToDesktop") { c ->
                c.moveTaskToDesktop(taskId, transitionSource = transitionSource)
            }
        }

        override fun removeDesktop(displayId: Int) {
            executeRemoteCallWithTaskPermission(controller, "removeDesktop") { c ->
                c.removeDesktop(displayId)
            }
        }

        override fun moveToExternalDisplay(taskId: Int) {
            executeRemoteCallWithTaskPermission(controller, "moveTaskToExternalDisplay") { c ->
                c.moveToNextDisplay(taskId)
            }
        }
    }

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }
    private fun logD(msg: String, vararg arguments: Any?) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }
    private fun logW(msg: String, vararg arguments: Any?) {
        ProtoLog.w(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        @JvmField
        val DESKTOP_MODE_INITIAL_BOUNDS_SCALE =
            SystemProperties.getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f

        private const val TAG = "DesktopTasksController"
    }

    /** Defines interface for classes that can listen to changes for task resize. */
    // TODO(b/343931111): Migrate to using TransitionObservers when ready
    interface TaskbarDesktopTaskListener {
        /**
         * [hasTasksRequiringTaskbarRounding] is true when a task is either maximized or snapped
         * left/right and rounded corners are enabled.
         */
        fun onTaskbarCornerRoundingUpdate(hasTasksRequiringTaskbarRounding: Boolean)
    }

    /** The positions on a screen that a task can snap to. */
    enum class SnapPosition {
        RIGHT,
        LEFT
    }
}
