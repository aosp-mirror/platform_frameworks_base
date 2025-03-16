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
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.SystemProperties
import android.os.UserHandle
import android.util.Size
import android.view.Display.DEFAULT_DISPLAY
import android.view.DragEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_PIP
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.widget.Toast
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
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE
import com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_SNAP_RESIZE
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.Flags.enableFlexibleSplit
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.MultiInstanceHelper.Companion.getComponent
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SingleInstanceRemoteListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.compatui.isTopActivityExemptFromDesktopWindowing
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopModeUiEventLogger.DesktopUiEventEnum
import com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.DragStartState
import com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.IndicatorType
import com.android.wm.shell.desktopmode.DesktopRepository.VisibleTasksListener
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler.Companion.DRAG_TO_DESKTOP_FINISH_ANIM_DURATION_MS
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler.DragToDesktopStateListener
import com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FREEFORM_ANIMATION_DURATION
import com.android.wm.shell.desktopmode.ExitDesktopTaskTransitionHandler.FULLSCREEN_ANIMATION_DURATION
import com.android.wm.shell.desktopmode.common.ToggleTaskSizeInteraction
import com.android.wm.shell.desktopmode.minimize.DesktopWindowLimitRemoteHandler
import com.android.wm.shell.draganddrop.DragAndDropController
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.recents.RecentTasksController
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.recents.RecentsTransitionStateListener.RecentsTransitionState
import com.android.wm.shell.recents.RecentsTransitionStateListener.TRANSITION_STATE_NOT_RUNNING
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.shared.annotations.ExternalThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus.DESKTOP_DENSITY_OVERRIDE
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus.useDesktopOverrideDensity
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_INDEX_UNDEFINED
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.UserChangeListener
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
import java.util.concurrent.TimeUnit
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
    private val keyguardManager: KeyguardManager,
    private val returnToDragStartAnimator: ReturnToDragStartAnimator,
    private val desktopMixedTransitionHandler: DesktopMixedTransitionHandler,
    private val enterDesktopTaskTransitionHandler: EnterDesktopTaskTransitionHandler,
    private val exitDesktopTaskTransitionHandler: ExitDesktopTaskTransitionHandler,
    private val desktopModeDragAndDropTransitionHandler: DesktopModeDragAndDropTransitionHandler,
    private val toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler,
    private val dragToDesktopTransitionHandler: DragToDesktopTransitionHandler,
    private val desktopImmersiveController: DesktopImmersiveController,
    private val userRepositories: DesktopUserRepositories,
    private val recentsTransitionHandler: RecentsTransitionHandler,
    private val multiInstanceHelper: MultiInstanceHelper,
    @ShellMainThread private val mainExecutor: ShellExecutor,
    private val desktopTasksLimiter: Optional<DesktopTasksLimiter>,
    private val recentTasksController: RecentTasksController?,
    private val interactionJankMonitor: InteractionJankMonitor,
    @ShellMainThread private val handler: Handler,
    private val desktopModeEventLogger: DesktopModeEventLogger,
    private val desktopModeUiEventLogger: DesktopModeUiEventLogger,
    private val desktopTilingDecorViewModel: DesktopTilingDecorViewModel,
) :
    RemoteCallable<DesktopTasksController>,
    Transitions.TransitionHandler,
    DragAndDropController.DragAndDropListener,
    UserChangeListener {

    private val desktopMode: DesktopModeImpl
    private var taskRepository: DesktopRepository
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

    @VisibleForTesting var taskbarDesktopTaskListener: TaskbarDesktopTaskListener? = null

    @VisibleForTesting
    var desktopModeEnterExitTransitionListener: DesktopModeEntryExitTransitionListener? = null

    /** Task id of the task currently being dragged from fullscreen/split. */
    val draggingTaskId
        get() = dragToDesktopTransitionHandler.draggingTaskId

    @RecentsTransitionState private var recentsTransitionState = TRANSITION_STATE_NOT_RUNNING

    private lateinit var splitScreenController: SplitScreenController
    lateinit var freeformTaskTransitionStarter: FreeformTaskTransitionStarter
    // Launch cookie used to identify a drag and drop transition to fullscreen after it has begun.
    // Used to prevent handleRequest from moving the new fullscreen task to freeform.
    private var dragAndDropFullscreenCookie: Binder? = null
    private var pendingPipTransitionAndTask: Pair<IBinder, Int>? = null

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            shellInit.addInitCallback({ onInit() }, this)
        }
        userId = ActivityManager.getCurrentUser()
        taskRepository = userRepositories.getProfile(userId)
    }

    private fun onInit() {
        logD("onInit")
        shellCommandHandler.addDumpCallback(this::dump, this)
        shellCommandHandler.addCommandCallback("desktopmode", desktopModeShellCommandHandler, this)
        shellController.addExternalInterface(
            IDesktopMode.DESCRIPTOR,
            { createExternalInterface() },
            this,
        )
        shellController.addUserChangeListener(this)
        transitions.addHandler(this)
        dragToDesktopTransitionHandler.dragToDesktopStateListener = dragToDesktopStateListener
        recentsTransitionHandler.addTransitionStateListener(
            object : RecentsTransitionStateListener {
                override fun onTransitionStateChanged(@RecentsTransitionState state: Int) {
                    logV(
                        "Recents transition state changed: %s",
                        RecentsTransitionStateListener.stateToString(state),
                    )
                    recentsTransitionState = state
                    desktopTilingDecorViewModel.onOverviewAnimationStateChange(
                        RecentsTransitionStateListener.isAnimating(state)
                    )
                }
            }
        )
        dragAndDropController.addListener(this)
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
        val handler =
            remoteTransition?.let {
                OneShotRemoteHandler(transitions.mainExecutor, remoteTransition)
            }
        transitions.startTransition(transitionType, wct, handler).also { t ->
            handler?.setTransition(t)
        }
    }

    /** Gets number of visible tasks in [displayId]. */
    fun visibleTaskCount(displayId: Int): Int = taskRepository.getVisibleTaskCount(displayId)

    /** Returns true if any tasks are visible in Desktop Mode. */
    fun isDesktopModeShowing(displayId: Int): Boolean = visibleTaskCount(displayId) > 0

    /** Moves focused task to desktop mode for given [displayId]. */
    fun moveFocusedTaskToDesktop(displayId: Int, transitionSource: DesktopModeTransitionSource) {
        val allFocusedTasks = getAllFocusedTasks(displayId)
        when (allFocusedTasks.size) {
            0 -> return
            // Full screen case
            1 ->
                moveRunningTaskToDesktop(
                    allFocusedTasks.single(),
                    transitionSource = transitionSource,
                )
            // Split-screen case where there are two focused tasks, then we find the child
            // task to move to desktop.
            2 ->
                moveRunningTaskToDesktop(
                    getSplitFocusedTask(allFocusedTasks[0], allFocusedTasks[1]),
                    transitionSource = transitionSource,
                )
            else ->
                logW(
                    "DesktopTasksController: Cannot enter desktop, expected less " +
                        "than 3 focused tasks but found %d",
                    allFocusedTasks.size,
                )
        }
    }

    /**
     * Returns all focused tasks in full screen or split screen mode in [displayId] when it is not
     * the home activity.
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
        // A non-organized display (e.g., non-trusted virtual displays used in CTS) doesn't have
        // TDA.
        if (tdaInfo == null) {
            logW(
                "forceEnterDesktop cannot find DisplayAreaInfo for displayId=%d. This could happen" +
                    " when the display is a non-trusted virtual display.",
                displayId,
            )
            return false
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
        remoteTransition: RemoteTransition? = null,
    ): Boolean {
        val runningTask = shellTaskOrganizer.getRunningTaskInfo(taskId)
        if (runningTask == null) {
            return moveBackgroundTaskToDesktop(taskId, wct, transitionSource, remoteTransition)
        }
        moveRunningTaskToDesktop(runningTask, wct, transitionSource, remoteTransition)
        return true
    }

    private fun moveBackgroundTaskToDesktop(
        taskId: Int,
        wct: WindowContainerTransaction,
        transitionSource: DesktopModeTransitionSource,
        remoteTransition: RemoteTransition? = null,
    ): Boolean {
        if (recentTasksController?.findTaskInBackground(taskId) == null) {
            logW("moveBackgroundTaskToDesktop taskId=%d not found", taskId)
            return false
        }
        logV("moveBackgroundTaskToDesktop with taskId=%d", taskId)
        // TODO(342378842): Instead of using default display, support multiple displays
        val taskIdToMinimize =
            bringDesktopAppsToFrontBeforeShowingNewTask(DEFAULT_DISPLAY, wct, taskId)
        val exitResult =
            desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                displayId = DEFAULT_DISPLAY,
                excludeTaskId = taskId,
                reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
            )
        wct.startTask(
            taskId,
            ActivityOptions.makeBasic()
                .apply { launchWindowingMode = WINDOWING_MODE_FREEFORM }
                .toBundle(),
        )

        val transition: IBinder
        if (remoteTransition != null) {
            val transitionType = transitionType(remoteTransition)
            val remoteTransitionHandler = OneShotRemoteHandler(mainExecutor, remoteTransition)
            transition = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
            remoteTransitionHandler.setTransition(transition)
        } else {
            // TODO(343149901): Add DPI changes for task launch
            transition = enterDesktopTaskTransitionHandler.moveToDesktop(wct, transitionSource)
        }
        desktopModeEnterExitTransitionListener?.onEnterDesktopModeTransitionStarted(
            FREEFORM_ANIMATION_DURATION
        )
        taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
        exitResult.asExit()?.runOnTransitionStart?.invoke(transition)
        return true
    }

    /** Moves a running task to desktop. */
    fun moveRunningTaskToDesktop(
        task: RunningTaskInfo,
        wct: WindowContainerTransaction = WindowContainerTransaction(),
        transitionSource: DesktopModeTransitionSource,
        remoteTransition: RemoteTransition? = null,
    ) {
        if (
            DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue() &&
                isTopActivityExemptFromDesktopWindowing(context, task)
        ) {
            logW("Cannot enter desktop for taskId %d, ineligible top activity found", task.taskId)
            return
        }
        logV("moveRunningTaskToDesktop taskId=%d", task.taskId)
        exitSplitIfApplicable(wct, task)
        val exitResult =
            desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                displayId = task.displayId,
                excludeTaskId = task.taskId,
                reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
            )

        // Bring other apps to front first
        val taskIdToMinimize =
            bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
        addMoveToDesktopChanges(wct, task)

        val transition: IBinder
        if (remoteTransition != null) {
            val transitionType = transitionType(remoteTransition)
            val remoteTransitionHandler = OneShotRemoteHandler(mainExecutor, remoteTransition)
            transition = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
            remoteTransitionHandler.setTransition(transition)
        } else {
            transition = enterDesktopTaskTransitionHandler.moveToDesktop(wct, transitionSource)
        }
        desktopModeEnterExitTransitionListener?.onEnterDesktopModeTransitionStarted(
            FREEFORM_ANIMATION_DURATION
        )
        taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
        exitResult.asExit()?.runOnTransitionStart?.invoke(transition)
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
        val jankConfigBuilder =
            InteractionJankMonitor.Configuration.Builder.withSurface(
                    CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD,
                    context,
                    taskSurface,
                    handler,
                )
                .setTimeout(APP_HANDLE_DRAG_HOLD_CUJ_TIMEOUT_MS)
        interactionJankMonitor.begin(jankConfigBuilder)
        dragToDesktopTransitionHandler.startDragToDesktopTransition(
            taskInfo,
            dragToDesktopValueAnimator,
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
            taskInfo.taskId,
        )
        val wct = WindowContainerTransaction()
        exitSplitIfApplicable(wct, taskInfo)
        moveHomeTask(wct, toTop = true)
        val taskIdToMinimize =
            bringDesktopAppsToFrontBeforeShowingNewTask(taskInfo.displayId, wct, taskInfo.taskId)
        addMoveToDesktopChanges(wct, taskInfo)
        val exitResult =
            desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                displayId = taskInfo.displayId,
                excludeTaskId = null,
                reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
            )
        val transition = dragToDesktopTransitionHandler.finishDragToDesktopTransition(wct)
        desktopModeEnterExitTransitionListener?.onEnterDesktopModeTransitionStarted(
            DRAG_TO_DESKTOP_FINISH_ANIM_DURATION_MS.toInt()
        )
        transition?.let {
            taskIdToMinimize?.let { taskId -> addPendingMinimizeTransition(it, taskId) }
            exitResult.asExit()?.runOnTransitionStart?.invoke(transition)
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
        performDesktopExitCleanupIfNeeded(taskId, wct)
        taskRepository.addClosingTask(displayId, taskId)
        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(displayId, taskId)
        )
        return desktopImmersiveController
            .exitImmersiveIfApplicable(
                wct = wct,
                taskInfo = taskInfo,
                reason = DesktopImmersiveController.ExitReason.CLOSED,
            )
            .asExit()
            ?.runOnTransitionStart
    }

    fun minimizeTask(taskInfo: RunningTaskInfo) {
        val wct = WindowContainerTransaction()

        val isMinimizingToPip = taskInfo.pictureInPictureParams?.isAutoEnterEnabled() ?: false
        // If task is going to PiP, start a PiP transition instead of a minimize transition
        if (isMinimizingToPip) {
            val requestInfo = TransitionRequestInfo(
                TRANSIT_PIP, /* triggerTask= */ null, taskInfo, /* remoteTransition= */ null,
                /* displayChange= */ null, /* flags= */ 0
            )
            val requestRes = transitions.dispatchRequest(Binder(), requestInfo, /* skip= */ null)
            wct.merge(requestRes.second, true)
            pendingPipTransitionAndTask =
                freeformTaskTransitionStarter.startPipTransition(wct) to taskInfo.taskId
            return
        }

        minimizeTaskInner(taskInfo)
    }

    private fun minimizeTaskInner(taskInfo: RunningTaskInfo) {
        val taskId = taskInfo.taskId
        val displayId = taskInfo.displayId
        val wct = WindowContainerTransaction()
        performDesktopExitCleanupIfNeeded(taskId, wct)
        // Notify immersive handler as it might need to exit immersive state.
        val exitResult =
            desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                taskInfo = taskInfo,
                reason = DesktopImmersiveController.ExitReason.MINIMIZED,
            )

        wct.reorder(taskInfo.token, false)
        val transition = freeformTaskTransitionStarter.startMinimizedModeTransition(wct)
        desktopTasksLimiter.ifPresent {
            it.addPendingMinimizeChange(
                transition = transition,
                displayId = displayId,
                taskId = taskId,
            )
        }
        exitResult.asExit()?.runOnTransitionStart?.invoke(transition)
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

    private fun exitSplitIfApplicable(wct: WindowContainerTransaction, taskInfo: RunningTaskInfo) {
        if (splitScreenController.isTaskInSplitScreen(taskInfo.taskId)) {
            splitScreenController.prepareExitSplitScreen(
                wct,
                splitScreenController.getStageOfTask(taskInfo.taskId),
                EXIT_REASON_DESKTOP_MODE,
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
        transitionSource: DesktopModeTransitionSource,
    ) {
        logV("moveToFullscreenWithAnimation taskId=%d", task.taskId)
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task)

        exitDesktopTaskTransitionHandler.startTransition(
            transitionSource,
            wct,
            position,
            mOnAnimationFinishedCallback,
        )

        // handles case where we are moving to full screen without closing all DW tasks.
        if (!taskRepository.isOnlyVisibleNonClosingTask(task.taskId)) {
            desktopModeEnterExitTransitionListener?.onExitDesktopModeTransitionStarted(
                FULLSCREEN_ANIMATION_DURATION
            )
        }
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
        wct.startTask(
            taskId,
            ActivityOptions.makeBasic()
                .apply { launchWindowingMode = WINDOWING_MODE_FREEFORM }
                .toBundle(),
        )
        startLaunchTransition(TRANSIT_OPEN, wct, taskId, remoteTransition = remoteTransition)
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
        startLaunchTransition(
            transitionType = TRANSIT_TO_FRONT,
            wct = wct,
            launchingTaskId = taskInfo.taskId,
            remoteTransition = remoteTransition,
            displayId = taskInfo.displayId,
        )
    }

    private fun startLaunchTransition(
        transitionType: Int,
        wct: WindowContainerTransaction,
        launchingTaskId: Int?,
        remoteTransition: RemoteTransition? = null,
        displayId: Int = DEFAULT_DISPLAY,
    ): IBinder {
        val taskIdToMinimize =
            if (launchingTaskId != null) {
                addAndGetMinimizeChanges(displayId, wct, newTaskId = launchingTaskId)
            } else {
                logW("Starting desktop task launch without checking the task-limit")
                // TODO(b/378920066): This currently does not respect the desktop window limit.
                //  It's possible that |launchingTaskId| is null when launching using an intent, and
                //  the task-limit should be respected then too.
                null
            }
        val exitImmersiveResult =
            desktopImmersiveController.exitImmersiveIfApplicable(
                wct = wct,
                displayId = displayId,
                excludeTaskId = launchingTaskId,
                reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
            )
        if (remoteTransition == null) {
            val t =
                desktopMixedTransitionHandler.startLaunchTransition(
                    transitionType = transitionType,
                    wct = wct,
                    taskId = launchingTaskId,
                    minimizingTaskId = taskIdToMinimize,
                    exitingImmersiveTask = exitImmersiveResult.asExit()?.exitingTask,
                )
            taskIdToMinimize?.let { addPendingMinimizeTransition(t, it) }
            exitImmersiveResult.asExit()?.runOnTransitionStart?.invoke(t)
            return t
        }
        if (taskIdToMinimize == null) {
            val remoteTransitionHandler = OneShotRemoteHandler(mainExecutor, remoteTransition)
            val t = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
            remoteTransitionHandler.setTransition(t)
            exitImmersiveResult.asExit()?.runOnTransitionStart?.invoke(t)
            return t
        }
        val remoteTransitionHandler =
            DesktopWindowLimitRemoteHandler(
                mainExecutor,
                rootTaskDisplayAreaOrganizer,
                remoteTransition,
                taskIdToMinimize,
            )
        val t = transitions.startTransition(transitionType, wct, remoteTransitionHandler)
        remoteTransitionHandler.setTransition(t)
        taskIdToMinimize.let { addPendingMinimizeTransition(t, it) }
        exitImmersiveResult.asExit()?.runOnTransitionStart?.invoke(t)
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

    /**
     * Quick-resizes a desktop task, toggling between a fullscreen state (represented by the stable
     * bounds) and a free floating state (either the last saved bounds if available or the default
     * bounds otherwise).
     */
    fun toggleDesktopTaskSize(taskInfo: RunningTaskInfo, interaction: ToggleTaskSizeInteraction) {
        val currentTaskBounds = taskInfo.configuration.windowConfiguration.bounds
        desktopModeEventLogger.logTaskResizingStarted(
            interaction.resizeTrigger,
            interaction.inputMethod,
            taskInfo,
            currentTaskBounds.width(),
            currentTaskBounds.height(),
            displayController,
        )
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return
        val destinationBounds = Rect()
        val isMaximized = interaction.direction == ToggleTaskSizeInteraction.Direction.RESTORE
        // If the task is currently maximized, we will toggle it not to be and vice versa. This is
        // helpful to eliminate the current task from logic to calculate taskbar corner rounding.
        val willMaximize = interaction.direction == ToggleTaskSizeInteraction.Direction.MAXIMIZE
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

        val doesAnyTaskRequireTaskbarRounding =
            willMaximize ||
                shouldRestoreToSnap ||
                doesAnyTaskRequireTaskbarRounding(taskInfo.displayId, taskInfo.taskId)

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(doesAnyTaskRequireTaskbarRounding)
        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
        interaction.uiEvent?.let { uiEvent -> desktopModeUiEventLogger.log(taskInfo, uiEvent) }
        desktopModeEventLogger.logTaskResizingEnded(
            interaction.resizeTrigger,
            interaction.inputMethod,
            taskInfo,
            destinationBounds.width(),
            destinationBounds.height(),
            displayController,
        )
        toggleResizeDesktopTaskTransitionHandler.startTransition(
            wct,
            interaction.animationStartBounds,
        )
    }

    private fun dragToMaximizeDesktopTask(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl,
        currentDragBounds: Rect,
        motionEvent: MotionEvent,
    ) {
        if (isTaskMaximized(taskInfo, displayController)) {
            // Handle the case where we attempt to drag-to-maximize when already maximized: the task
            // position won't need to change but we want to animate the surface going back to the
            // maximized position.
            val containerBounds = taskInfo.configuration.windowConfiguration.bounds
            if (containerBounds != currentDragBounds) {
                returnToDragStartAnimator.start(
                    taskInfo.taskId,
                    taskSurface,
                    startBounds = currentDragBounds,
                    endBounds = containerBounds,
                )
            }
            return
        }

        toggleDesktopTaskSize(
            taskInfo,
            ToggleTaskSizeInteraction(
                direction = ToggleTaskSizeInteraction.Direction.MAXIMIZE,
                source = ToggleTaskSizeInteraction.Source.HEADER_DRAG_TO_TOP,
                inputMethod = DesktopModeEventLogger.getInputMethodFromMotionEvent(motionEvent),
                animationStartBounds = currentDragBounds,
            ),
        )
    }

    private fun getMaximizeBounds(taskInfo: RunningTaskInfo, stableBounds: Rect): Rect {
        if (taskInfo.isResizeable) {
            // if resizable then expand to entire stable bounds (full display minus insets)
            return Rect(stableBounds)
        } else {
            // if non-resizable then calculate max bounds according to aspect ratio
            val activityAspectRatio = calculateAspectRatio(taskInfo)
            val newSize =
                maximizeSizeGivenAspectRatio(
                    taskInfo,
                    Size(stableBounds.width(), stableBounds.height()),
                    activityAspectRatio,
                )
            return centerInArea(newSize, stableBounds, stableBounds.left, stableBounds.top)
        }
    }

    private fun isMaximizedToStableBoundsEdges(
        taskInfo: RunningTaskInfo,
        stableBounds: Rect,
    ): Boolean {
        val currentTaskBounds = taskInfo.configuration.windowConfiguration.bounds
        return isTaskBoundsEqual(currentTaskBounds, stableBounds)
    }

    /** Returns if current task bound is snapped to half screen */
    private fun isTaskSnappedToHalfScreen(
        taskInfo: RunningTaskInfo,
        taskBounds: Rect = taskInfo.configuration.windowConfiguration.bounds,
    ): Boolean =
        getSnapBounds(taskInfo, SnapPosition.LEFT) == taskBounds ||
            getSnapBounds(taskInfo, SnapPosition.RIGHT) == taskBounds

    @VisibleForTesting
    fun doesAnyTaskRequireTaskbarRounding(displayId: Int, excludeTaskId: Int? = null): Boolean {
        val doesAnyTaskRequireTaskbarRounding =
            taskRepository
                .getExpandedTasksOrdered(displayId)
                // exclude current task since maximize/restore transition has not taken place yet.
                .filterNot { taskId -> taskId == excludeTaskId }
                .any { taskId ->
                    val taskInfo = shellTaskOrganizer.getRunningTaskInfo(taskId) ?: return false
                    val displayLayout = displayController.getDisplayLayout(taskInfo.displayId)
                    val stableBounds = Rect().apply { displayLayout?.getStableBounds(this) }
                    logD("taskInfo = %s", taskInfo)
                    logD(
                        "isTaskSnappedToHalfScreen(taskInfo) = %s",
                        isTaskSnappedToHalfScreen(taskInfo),
                    )
                    logD(
                        "isMaximizedToStableBoundsEdges(taskInfo, stableBounds) = %s",
                        isMaximizedToStableBoundsEdges(taskInfo, stableBounds),
                    )
                    isTaskSnappedToHalfScreen(taskInfo) ||
                        isMaximizedToStableBoundsEdges(taskInfo, stableBounds)
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
     *   bounds if being snapped resize via maximize menu button)
     * @param position the portion of the screen (RIGHT or LEFT) we want to snap the task to.
     */
    fun snapToHalfScreen(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl?,
        currentDragBounds: Rect,
        position: SnapPosition,
        resizeTrigger: ResizeTrigger,
        inputMethod: InputMethod,
        desktopWindowDecoration: DesktopModeWindowDecoration,
    ) {
        desktopModeEventLogger.logTaskResizingStarted(
            resizeTrigger,
            inputMethod,
            taskInfo,
            currentDragBounds.width(),
            currentDragBounds.height(),
            displayController,
        )

        val destinationBounds = getSnapBounds(taskInfo, position)
        desktopModeEventLogger.logTaskResizingEnded(
            resizeTrigger,
            inputMethod,
            taskInfo,
            destinationBounds.width(),
            destinationBounds.height(),
            displayController,
        )

        if (DesktopModeFlags.ENABLE_TILE_RESIZING.isTrue()) {
            val isTiled =
                desktopTilingDecorViewModel.snapToHalfScreen(
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

        if (destinationBounds == taskInfo.configuration.windowConfiguration.bounds) {
            // Handle the case where we attempt to snap resize when already snap resized: the task
            // position won't need to change but we want to animate the surface going back to the
            // snapped position from the "dragged-to-the-edge" position.
            if (destinationBounds != currentDragBounds && taskSurface != null) {
                returnToDragStartAnimator.start(
                    taskInfo.taskId,
                    taskSurface,
                    startBounds = currentDragBounds,
                    endBounds = destinationBounds,
                )
            }
            return
        }

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(true)
        val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)

        toggleResizeDesktopTaskTransitionHandler.startTransition(wct, currentDragBounds)
    }

    /**
     * Handles snap resizing a [taskInfo] to [position] instantaneously, for example when the
     * [resizeTrigger] is the snap resize menu using any [motionEvent] or a keyboard shortcut.
     */
    fun handleInstantSnapResizingTask(
        taskInfo: RunningTaskInfo,
        position: SnapPosition,
        resizeTrigger: ResizeTrigger,
        inputMethod: InputMethod,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
    ) {
        if (!isSnapResizingAllowed(taskInfo)) {
            Toast.makeText(
                    getContext(),
                    R.string.desktop_mode_non_resizable_snap_text,
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }

        snapToHalfScreen(
            taskInfo,
            null,
            taskInfo.configuration.windowConfiguration.bounds,
            position,
            resizeTrigger,
            inputMethod,
            desktopModeWindowDecoration,
        )
    }

    @VisibleForTesting
    fun handleSnapResizingTaskOnDrag(
        taskInfo: RunningTaskInfo,
        position: SnapPosition,
        taskSurface: SurfaceControl,
        currentDragBounds: Rect,
        dragStartBounds: Rect,
        motionEvent: MotionEvent,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
    ) {
        releaseVisualIndicator()
        if (!isSnapResizingAllowed(taskInfo)) {
            interactionJankMonitor.begin(
                taskSurface,
                context,
                handler,
                CUJ_DESKTOP_MODE_SNAP_RESIZE,
                "drag_non_resizable",
            )

            // reposition non-resizable app back to its original position before being dragged
            returnToDragStartAnimator.start(
                taskInfo.taskId,
                taskSurface,
                startBounds = currentDragBounds,
                endBounds = dragStartBounds,
                doOnEnd = {
                    Toast.makeText(
                            context,
                            com.android.wm.shell.R.string.desktop_mode_non_resizable_snap_text,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                },
            )
        } else {
            val resizeTrigger =
                if (position == SnapPosition.LEFT) {
                    ResizeTrigger.DRAG_LEFT
                } else {
                    ResizeTrigger.DRAG_RIGHT
                }
            interactionJankMonitor.begin(
                taskSurface,
                context,
                handler,
                CUJ_DESKTOP_MODE_SNAP_RESIZE,
                "drag_resizable",
            )
            snapToHalfScreen(
                taskInfo,
                taskSurface,
                currentDragBounds,
                position,
                resizeTrigger,
                DesktopModeEventLogger.getInputMethodFromMotionEvent(motionEvent),
                desktopModeWindowDecoration,
            )
        }
    }

    private fun isSnapResizingAllowed(taskInfo: RunningTaskInfo) =
        taskInfo.isResizeable || !DISABLE_NON_RESIZABLE_APP_SNAP_RESIZE.isTrue()

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
                    stableBounds.bottom,
                )
            }
            SnapPosition.RIGHT -> {
                Rect(
                    stableBounds.right - destinationWidth,
                    stableBounds.top,
                    stableBounds.right,
                    stableBounds.bottom,
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
        newTaskIdInFront: Int,
    ): Int? = bringDesktopAppsToFront(displayId, wct, newTaskIdInFront)

    private fun bringDesktopAppsToFront(
        displayId: Int,
        wct: WindowContainerTransaction,
        newTaskIdInFront: Int? = null,
    ): Int? {
        logV("bringDesktopAppsToFront, newTaskId=%d", newTaskIdInFront)
        // Move home to front, ensures that we go back home when all desktop windows are closed
        moveHomeTask(wct, toTop = true)

        // Currently, we only handle the desktop on the default display really.
        if (
            (displayId == DEFAULT_DISPLAY || Flags.enableBugFixesForSecondaryDisplay()) &&
                ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()
        ) {
            // Add translucent wallpaper activity to show the wallpaper underneath
            addWallpaperActivity(displayId, wct)
        }

        val expandedTasksOrderedFrontToBack = taskRepository.getExpandedTasksOrdered(displayId)
        // If we're adding a new Task we might need to minimize an old one
        // TODO(b/365725441): Handle non running task minimization
        val taskIdToMinimize: Int? =
            if (newTaskIdInFront != null && desktopTasksLimiter.isPresent) {
                desktopTasksLimiter
                    .get()
                    .getTaskIdToMinimize(expandedTasksOrderedFrontToBack, newTaskIdInFront)
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
                } else if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) {
                    // Task is not running, start it
                    wct.startTask(
                        taskId,
                        ActivityOptions.makeBasic()
                            .apply { launchWindowingMode = WINDOWING_MODE_FREEFORM }
                            .toBundle(),
                    )
                }
            }

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(displayId)
        )

        return taskIdToMinimize
    }

    private fun moveHomeTask(wct: WindowContainerTransaction, toTop: Boolean) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), /* onTop= */ toTop) }
    }

    private fun addWallpaperActivity(displayId: Int, wct: WindowContainerTransaction) {
        logV("addWallpaperActivity")
        val userHandle = UserHandle.of(userId)
        val userContext = context.createContextAsUser(userHandle, /* flags= */ 0)
        val intent = Intent(userContext, DesktopWallpaperActivity::class.java)
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId)
        val options =
            ActivityOptions.makeBasic().apply {
                launchWindowingMode = WINDOWING_MODE_FULLSCREEN
                pendingIntentBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                if (Flags.enableBugFixesForSecondaryDisplay()) {
                    launchDisplayId = displayId
                }
            }
        val pendingIntent =
            PendingIntent.getActivityAsUser(
                userContext,
                /* requestCode= */ 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
                /* options= */ null,
                userHandle,
            )
        wct.sendPendingIntent(pendingIntent, intent, options.toBundle())
    }

    private fun removeWallpaperActivity(wct: WindowContainerTransaction) {
        taskRepository.wallpaperActivityToken?.let { token ->
            logV("removeWallpaperActivity")
            wct.removeTask(token)
        }
    }

    /**
     * Remove wallpaper activity if task provided is last task and wallpaper activity token is not
     * null
     */
    private fun performDesktopExitCleanupIfNeeded(taskId: Int, wct: WindowContainerTransaction) {
        if (!taskRepository.isOnlyVisibleNonClosingTask(taskId)) {
            return
        }
        desktopModeEnterExitTransitionListener?.onExitDesktopModeTransitionStarted(
            FULLSCREEN_ANIMATION_DURATION
        )
        if (taskRepository.wallpaperActivityToken != null) {
            removeWallpaperActivity(wct)
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
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        // This handler should never be the sole handler, so should not animate anything.
        return false
    }

    override fun onTransitionConsumed(
        transition: IBinder,
        aborted: Boolean,
        finishT: Transaction?
    ) {
        pendingPipTransitionAndTask?.let { (pipTransition, taskId) ->
            if (transition == pipTransition) {
                if (aborted) {
                    shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { minimizeTaskInner(it) }
                }
                pendingPipTransitionAndTask = null
            }
        }
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo,
    ): WindowContainerTransaction? {
        logV("handleRequest request=%s", request)
        // Check if we should skip handling this transition
        var reason = ""
        val triggerTask = request.triggerTask
        val recentsAnimationRunning =
            RecentsTransitionStateListener.isAnimating(recentsTransitionState)
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
                    TransitionUtil.isClosingType(request.type) ->
                        handleTaskClosing(task, transition, request.type)
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
    fun isDesktopChange(transition: IBinder, change: TransitionInfo.Change): Boolean {
        // Only the immersive controller is currently involved in mixed transitions.
        return Flags.enableFullyImmersiveInDesktop() &&
            desktopImmersiveController.isImmersiveChange(transition, change)
    }

    /**
     * Whether the given transition [info] will potentially include a desktop change, in which case
     * the transition should be treated as mixed so that the change is in part animated by one of
     * the desktop transition handlers.
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
            finishCallback,
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
        val cornerRadius =
            context.resources
                .getDimensionPixelSize(R.dimen.desktop_windowing_freeform_rounded_corner_radius)
                .toFloat()
        info.changes
            .filter { it.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM }
            .forEach { finishTransaction.setCornerRadius(it.leash, cornerRadius) }
    }

    /** Returns whether an existing desktop task is being relaunched in freeform or not. */
    private fun isFreeformRelaunch(triggerTask: RunningTaskInfo?, request: TransitionRequestInfo) =
        (triggerTask != null &&
            triggerTask.windowingMode == WINDOWING_MODE_FREEFORM &&
            TransitionUtil.isOpeningType(request.type) &&
            taskRepository.isActiveTask(triggerTask.taskId))

    private fun isIncompatibleTask(task: TaskInfo) =
        DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue() &&
            isTopActivityExemptFromDesktopWindowing(context, task)

    private fun shouldHandleTaskClosing(request: TransitionRequestInfo): Boolean {
        return ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue() &&
            TransitionUtil.isClosingType(request.type) &&
            request.triggerTask != null
    }

    /** Open an existing instance of an app. */
    fun openInstance(callingTask: RunningTaskInfo, requestedTaskId: Int) {
        if (callingTask.isFreeform) {
            val requestedTaskInfo = shellTaskOrganizer.getRunningTaskInfo(requestedTaskId)
            if (requestedTaskInfo?.isFreeform == true) {
                // If requested task is an already open freeform task, just move it to front.
                moveTaskToFront(requestedTaskId)
            } else {
                moveBackgroundTaskToDesktop(
                    requestedTaskId,
                    WindowContainerTransaction(),
                    DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON,
                )
            }
        } else {
            val options = createNewWindowOptions(callingTask)
            val splitPosition = splitScreenController.determineNewInstancePosition(callingTask)
            splitScreenController.startTask(
                requestedTaskId,
                splitPosition,
                options.toBundle(),
                null, /* hideTaskToken */
            )
        }
    }

    /** Create an Intent to open a new window of a task. */
    fun openNewWindow(callingTaskInfo: RunningTaskInfo) {
        // TODO(b/337915660): Add a transition handler for these; animations
        //  need updates in some cases.
        val baseActivity = callingTaskInfo.baseActivity ?: return
        val fillIn: Intent =
            context.packageManager.getLaunchIntentForPackage(baseActivity.packageName) ?: return
        fillIn.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val launchIntent =
            PendingIntent.getActivity(
                context,
                /* requestCode= */ 0,
                fillIn,
                PendingIntent.FLAG_IMMUTABLE,
            )
        val options = createNewWindowOptions(callingTaskInfo)
        when (options.launchWindowingMode) {
            WINDOWING_MODE_MULTI_WINDOW -> {
                val splitPosition =
                    splitScreenController.determineNewInstancePosition(callingTaskInfo)
                // TODO(b/349828130) currently pass in index_undefined until we can revisit these
                //  specific cases in the future.
                val splitIndex =
                    if (enableFlexibleSplit())
                        splitScreenController.determineNewInstanceIndex(callingTaskInfo)
                    else SPLIT_INDEX_UNDEFINED
                splitScreenController.startIntent(
                    launchIntent,
                    context.userId,
                    fillIn,
                    splitPosition,
                    options.toBundle(),
                    null /* hideTaskToken */,
                    true /* forceLaunchNewTask */,
                    splitIndex,
                )
            }
            WINDOWING_MODE_FREEFORM -> {
                val wct = WindowContainerTransaction()
                wct.sendPendingIntent(launchIntent, fillIn, options.toBundle())
                startLaunchTransition(
                    transitionType = TRANSIT_OPEN,
                    wct = wct,
                    launchingTaskId = null,
                    displayId = callingTaskInfo.displayId,
                )
            }
        }
    }

    private fun createNewWindowOptions(callingTask: RunningTaskInfo): ActivityOptions {
        val newTaskWindowingMode =
            when {
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
        val bounds =
            when (newTaskWindowingMode) {
                WINDOWING_MODE_FREEFORM -> {
                    displayController.getDisplayLayout(callingTask.displayId)?.let {
                        getInitialBounds(it, callingTask, callingTask.displayId)
                    }
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
        transition: IBinder,
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
        if (
            DesktopModeFlags.ENABLE_CASCADING_WINDOWS.isTrue() &&
                !taskRepository.isVisibleTask(task.taskId)
        ) {
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
        desktopImmersiveController.exitImmersiveIfApplicable(
            transition = transition,
            wct = wct,
            displayId = task.displayId,
            reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
        )
        // 2) minimize a Task if needed.
        val taskIdToMinimize = addAndGetMinimizeChanges(task.displayId, wct, task.taskId)
        addPendingAppLaunchTransition(transition, task.taskId, taskIdToMinimize)
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
        transition: IBinder,
    ): WindowContainerTransaction? {
        logV("handleFullscreenTaskLaunch")
        if (shouldFullscreenTaskLaunchSwitchToDesktop(task)) {
            logD("Switch fullscreen task to freeform on transition: taskId=%d", task.taskId)
            return WindowContainerTransaction().also { wct ->
                addMoveToDesktopChanges(wct, task)
                // In some launches home task is moved behind new task being launched. Make sure
                // that's not the case for launches in desktop. Also, if this launch is the first
                // one to trigger the desktop mode (e.g., when [forceEnterDesktop()]), activate the
                // desktop mode here.
                if (
                    task.baseIntent.flags.and(Intent.FLAG_ACTIVITY_TASK_ON_HOME) != 0 ||
                        !isDesktopModeShowing(task.displayId)
                ) {
                    bringDesktopAppsToFrontBeforeShowingNewTask(task.displayId, wct, task.taskId)
                    wct.reorder(task.token, true)
                }

                // Desktop Mode is already showing and we're launching a new Task - we might need to
                // minimize another Task.
                val taskIdToMinimize = addAndGetMinimizeChanges(task.displayId, wct, task.taskId)
                taskIdToMinimize?.let { addPendingMinimizeTransition(transition, it) }
                addPendingAppLaunchTransition(transition, task.taskId, taskIdToMinimize)
                desktopImmersiveController.exitImmersiveIfApplicable(
                    transition,
                    wct,
                    task.displayId,
                    reason = DesktopImmersiveController.ExitReason.TASK_LAUNCH,
                )
            }
        } else if (taskRepository.isActiveTask(task.taskId)) {
            // If a freeform task receives a request for a fullscreen launch, apply the same
            // changes we do for similar transitions. The task not having WINDOWING_MODE_UNDEFINED
            // set when needed can interfere with future split / multi-instance transitions.
            return WindowContainerTransaction().also { wct ->
                addMoveToFullscreenChanges(wct, task)
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
    private fun handleTaskClosing(
        task: RunningTaskInfo,
        transition: IBinder,
        requestType: Int,
    ): WindowContainerTransaction? {
        logV("handleTaskClosing")
        if (!isDesktopModeShowing(task.displayId)) return null

        val wct = WindowContainerTransaction()
        performDesktopExitCleanupIfNeeded(task.taskId, wct)

        if (!DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) {
            taskRepository.addClosingTask(task.displayId, task.taskId)
            desktopTilingDecorViewModel.removeTaskIfTiled(task.displayId, task.taskId)
        }

        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(task.displayId, task.taskId)
        )
        return if (wct.isEmpty) null else wct
    }

    /**
     * Apply all changes required when task is first added to desktop. Uses the task's current
     * display by default to apply initial bounds and placement relative to the display. Use a
     * different [displayId] if the task should be moved to a different display.
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
        val bounds =
            if (ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS.isTrue) {
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
        taskInfo: RunningTaskInfo,
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

        performDesktopExitCleanupIfNeeded(taskInfo.taskId, wct)
    }

    private fun cascadeWindow(bounds: Rect, displayLayout: DisplayLayout, displayId: Int) {
        val stableBounds = Rect()
        displayLayout.getStableBoundsForDesktopMode(stableBounds)

        val activeTasks = taskRepository.getExpandedTasksOrdered(displayId)
        activeTasks.firstOrNull()?.let { activeTask ->
            shellTaskOrganizer.getRunningTaskInfo(activeTask)?.let {
                cascadeWindow(
                    context.resources,
                    stableBounds,
                    it.configuration.windowConfiguration.bounds,
                    bounds,
                )
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

        performDesktopExitCleanupIfNeeded(taskInfo.taskId, wct)
    }

    /** Returns the ID of the Task that will be minimized, or null if no task will be minimized. */
    private fun addAndGetMinimizeChanges(
        displayId: Int,
        wct: WindowContainerTransaction,
        newTaskId: Int,
    ): Int? {
        if (!desktopTasksLimiter.isPresent) return null
        return desktopTasksLimiter.get().addAndGetMinimizeTaskChanges(displayId, wct, newTaskId)
    }

    private fun addPendingMinimizeTransition(transition: IBinder, taskIdToMinimize: Int) {
        val taskToMinimize = shellTaskOrganizer.getRunningTaskInfo(taskIdToMinimize)
        desktopTasksLimiter.ifPresent {
            it.addPendingMinimizeChange(
                transition = transition,
                displayId = taskToMinimize?.displayId ?: DEFAULT_DISPLAY,
                taskId = taskIdToMinimize,
            )
        }
    }

    private fun addPendingAppLaunchTransition(
        transition: IBinder,
        launchTaskId: Int,
        minimizeTaskId: Int?,
    ) {
        if (
            !DesktopModeFlags.ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS.isTrue &&
                !DesktopModeFlags.ENABLE_DESKTOP_APP_LAUNCH_TRANSITIONS_BUGFIX.isTrue
        ) {
            return
        }
        // TODO b/359523924: pass immersive task here?
        desktopMixedTransitionHandler.addPendingMixedTransition(
            DesktopMixedTransitionHandler.PendingMixedTransition.Launch(
                transition,
                launchTaskId,
                minimizeTaskId,
                /* exitingImmersiveTask= */ null,
            )
        )
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

    private fun getFocusedFreeformTask(displayId: Int): RunningTaskInfo? {
        return shellTaskOrganizer.getRunningTasks(displayId).find { taskInfo ->
            taskInfo.isFocused && taskInfo.windowingMode == WINDOWING_MODE_FREEFORM
        }
    }

    /**
     * Requests a task be transitioned from desktop to split select. Applies needed windowing
     * changes if this transition is enabled.
     */
    @JvmOverloads
    fun requestSplit(taskInfo: RunningTaskInfo, leftOrTop: Boolean = false) {
        // If a drag to desktop is in progress, we want to enter split select
        // even if the requesting task is already in split.
        val isDragging = dragToDesktopTransitionHandler.inProgress
        val shouldRequestSplit = taskInfo.isFullscreen || taskInfo.isFreeform || isDragging
        if (shouldRequestSplit) {
            if (isDragging) {
                releaseVisualIndicator()
                val cancelState =
                    if (leftOrTop) {
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
                    taskInfo.configuration.windowConfiguration.bounds,
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
        taskBounds: Rect,
    ) {
        if (taskInfo.windowingMode != WINDOWING_MODE_FREEFORM) return
        desktopTilingDecorViewModel.removeTaskIfTiled(taskInfo.displayId, taskInfo.taskId)
        updateVisualIndicator(
            taskInfo,
            taskSurface,
            inputX,
            taskBounds.top.toFloat(),
            DragStartState.FROM_FREEFORM,
        )
    }

    fun updateVisualIndicator(
        taskInfo: RunningTaskInfo,
        taskSurface: SurfaceControl?,
        inputX: Float,
        taskTop: Float,
        dragStartState: DragStartState,
    ): DesktopModeVisualIndicator.IndicatorType {
        // If the visual indicator does not exist, create it.
        val indicator =
            visualIndicator
                ?: DesktopModeVisualIndicator(
                    syncQueue,
                    taskInfo,
                    displayController,
                    if (Flags.enableBugFixesForSecondaryDisplay()) {
                        displayController.getDisplayContext(taskInfo.displayId)
                    } else {
                        context
                    },
                    taskSurface,
                    rootTaskDisplayAreaOrganizer,
                    dragStartState,
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
     *   task bounds or just task leash)
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
                PointF(inputCoordinate.x, currentDragBounds.top.toFloat())
            )
        when (indicatorType) {
            IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                if (DesktopModeStatus.shouldMaximizeWhenDragToTopEdge(context)) {
                    dragToMaximizeDesktopTask(taskInfo, taskSurface, currentDragBounds, motionEvent)
                } else {
                    desktopModeUiEventLogger.log(
                        taskInfo,
                        DesktopUiEventEnum.DESKTOP_WINDOW_APP_HEADER_DRAG_TO_FULL_SCREEN,
                    )
                    moveToFullscreenWithAnimation(
                        taskInfo,
                        position,
                        DesktopModeTransitionSource.TASK_DRAG,
                    )
                }
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HEADER_DRAG_TO_TILE_TO_LEFT,
                )
                handleSnapResizingTaskOnDrag(
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
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HEADER_DRAG_TO_TILE_TO_RIGHT,
                )
                handleSnapResizingTaskOnDrag(
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
                // Create a copy so that we can animate from the current bounds if we end up having
                // to snap the surface back without a WCT change.
                val destinationBounds = Rect(currentDragBounds)
                // If task bounds are outside valid drag area, snap them inward
                DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(
                    destinationBounds,
                    validDragArea,
                )

                if (destinationBounds == dragStartBounds) {
                    // There's no actual difference between the start and end bounds, so while a
                    // WCT change isn't needed, the dragged surface still needs to be snapped back
                    // to its original location.
                    releaseVisualIndicator()
                    returnToDragStartAnimator.start(
                        taskInfo.taskId,
                        taskSurface,
                        startBounds = currentDragBounds,
                        endBounds = dragStartBounds,
                    )
                    return
                }

                // Update task bounds so that the task position will match the position of its leash
                val wct = WindowContainerTransaction()
                wct.setBounds(taskInfo.token, destinationBounds)
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
        taskbarDesktopTaskListener?.onTaskbarCornerRoundingUpdate(
            doesAnyTaskRequireTaskbarRounding(taskInfo.displayId)
        )
    }

    /**
     * Cancel the drag-to-desktop transition.
     *
     * @param taskInfo the task being dragged.
     */
    fun onDragPositioningCancelThroughStatusBar(taskInfo: RunningTaskInfo) {
        interactionJankMonitor.cancel(CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD)
        cancelDragToDesktop(taskInfo)
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
                interactionJankMonitor.begin(
                    taskSurface,
                    context,
                    handler,
                    CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_RELEASE,
                    "to_desktop",
                )
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_DRAG_TO_DESKTOP_MODE,
                )
                finalizeDragToDesktop(taskInfo)
            }
            IndicatorType.NO_INDICATOR,
            IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_DRAG_TO_FULL_SCREEN,
                )
                cancelDragToDesktop(taskInfo)
            }
            IndicatorType.TO_SPLIT_LEFT_INDICATOR -> {
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_DRAG_TO_SPLIT_SCREEN,
                )
                requestSplit(taskInfo, leftOrTop = true)
            }
            IndicatorType.TO_SPLIT_RIGHT_INDICATOR -> {
                desktopModeUiEventLogger.log(
                    taskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_DRAG_TO_SPLIT_SCREEN,
                )
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
        onFinishCallback: Consumer<Boolean>,
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
        val indicatorType =
            updateVisualIndicator(
                taskInfo,
                dragEvent.dragSurface,
                dragEvent.x,
                dragEvent.y,
                DragStartState.DRAGGED_INTENT,
            )
        releaseVisualIndicator()
        val windowingMode =
            when (indicatorType) {
                IndicatorType.TO_FULLSCREEN_INDICATOR -> {
                    WINDOWING_MODE_FULLSCREEN
                }
                IndicatorType.TO_SPLIT_LEFT_INDICATOR,
                IndicatorType.TO_SPLIT_RIGHT_INDICATOR,
                IndicatorType.TO_DESKTOP_INDICATOR -> {
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
                    dragEvent.y.toInt(),
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
        logV("onUserChanged previousUserId=%d, newUserId=%d", userId, newUserId)
        userId = newUserId
        taskRepository = userRepositories.getProfile(userId)
        desktopTilingDecorViewModel.onUserChange()
    }

    /** Called when a task's info changes. */
    fun onTaskInfoChanged(taskInfo: RunningTaskInfo) {
        if (!Flags.enableFullyImmersiveInDesktop()) return
        val inImmersive = taskRepository.isTaskInFullImmersiveState(taskInfo.taskId)
        val requestingImmersive = taskInfo.requestingImmersive
        if (
            inImmersive &&
                !requestingImmersive &&
                !RecentsTransitionStateListener.isRunning(recentsTransitionState)
        ) {
            // Exit immersive if the app is no longer requesting it.
            desktopImmersiveController.moveTaskToNonImmersive(
                taskInfo,
                DesktopImmersiveController.ExitReason.APP_NOT_IMMERSIVE,
            )
        }
    }

    private fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("${prefix}DesktopTasksController")
        DesktopModeStatus.dump(pw, innerPrefix, context)
        pw.println("${prefix}userId=$userId")
        taskRepository.dump(pw, innerPrefix)
    }

    /** The interface for calls from outside the shell, within the host process. */
    @ExternalThread
    private inner class DesktopModeImpl : DesktopMode {
        override fun addVisibleTasksListener(
            listener: VisibleTasksListener,
            callbackExecutor: Executor,
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.addVisibleTasksListener(listener, callbackExecutor)
            }
        }

        override fun addDesktopGestureExclusionRegionListener(
            listener: Consumer<Region>,
            callbackExecutor: Executor,
        ) {
            mainExecutor.execute {
                this@DesktopTasksController.setTaskRegionListener(listener, callbackExecutor)
            }
        }

        override fun moveFocusedTaskToDesktop(
            displayId: Int,
            transitionSource: DesktopModeTransitionSource,
        ) {
            logV("moveFocusedTaskToDesktop")
            mainExecutor.execute {
                this@DesktopTasksController.moveFocusedTaskToDesktop(displayId, transitionSource)
            }
        }

        override fun moveFocusedTaskToFullscreen(
            displayId: Int,
            transitionSource: DesktopModeTransitionSource,
        ) {
            logV("moveFocusedTaskToFullscreen")
            mainExecutor.execute {
                this@DesktopTasksController.enterFullscreen(displayId, transitionSource)
            }
        }

        override fun moveFocusedTaskToStageSplit(displayId: Int, leftOrTop: Boolean) {
            logV("moveFocusedTaskToStageSplit")
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
                        visibleTasksCount,
                    )
                    remoteListener.call { l ->
                        l.onTasksVisibilityChanged(displayId, visibleTasksCount)
                    }
                }
            }

        private val taskbarDesktopTaskListener: TaskbarDesktopTaskListener =
            object : TaskbarDesktopTaskListener {
                override fun onTaskbarCornerRoundingUpdate(
                    hasTasksRequiringTaskbarRounding: Boolean
                ) {
                    ProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onTaskbarCornerRoundingUpdate " +
                            "doesAnyTaskRequireTaskbarRounding=%s",
                        hasTasksRequiringTaskbarRounding,
                    )

                    remoteListener.call { l ->
                        l.onTaskbarCornerRoundingUpdate(hasTasksRequiringTaskbarRounding)
                    }
                }
            }

        private val desktopModeEntryExitTransitionListener: DesktopModeEntryExitTransitionListener =
            object : DesktopModeEntryExitTransitionListener {
                override fun onEnterDesktopModeTransitionStarted(transitionDuration: Int) {
                    ProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onEnterDesktopModeTransitionStarted transitionTime=%s",
                        transitionDuration,
                    )
                    remoteListener.call { l ->
                        l.onEnterDesktopModeTransitionStarted(transitionDuration)
                    }
                }

                override fun onExitDesktopModeTransitionStarted(transitionDuration: Int) {
                    ProtoLog.v(
                        WM_SHELL_DESKTOP_MODE,
                        "IDesktopModeImpl: onExitDesktopModeTransitionStarted transitionTime=%s",
                        transitionDuration,
                    )
                    remoteListener.call { l ->
                        l.onExitDesktopModeTransitionStarted(transitionDuration)
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
                            c.taskbarDesktopTaskListener = taskbarDesktopTaskListener
                            c.desktopModeEnterExitTransitionListener =
                                desktopModeEntryExitTransitionListener
                        }
                    },
                    { c ->
                        run {
                            c.taskRepository.removeVisibleTasksListener(listener)
                            c.taskbarDesktopTaskListener = null
                            c.desktopModeEnterExitTransitionListener = null
                        }
                    },
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
            ProtoLog.w(
                WM_SHELL_DESKTOP_MODE,
                "IDesktopModeImpl: hideStashedDesktopApps is deprecated",
            )
        }

        override fun getVisibleTaskCount(displayId: Int): Int {
            val result = IntArray(1)
            executeRemoteCallWithTaskPermission(
                controller,
                "visibleTaskCount",
                { controller -> result[0] = controller.visibleTaskCount(displayId) },
                true, /* blocking */
            )
            return result[0]
        }

        override fun onDesktopSplitSelectAnimComplete(taskInfo: RunningTaskInfo) {
            executeRemoteCallWithTaskPermission(controller, "onDesktopSplitSelectAnimComplete") { c
                ->
                c.onDesktopSplitSelectAnimComplete(taskInfo)
            }
        }

        override fun setTaskListener(listener: IDesktopTaskListener?) {
            ProtoLog.v(WM_SHELL_DESKTOP_MODE, "IDesktopModeImpl: set task listener=%s", listener)
            executeRemoteCallWithTaskPermission(controller, "setTaskListener") { _ ->
                listener?.let { remoteListener.register(it) } ?: remoteListener.unregister()
            }
        }

        override fun moveToDesktop(
            taskId: Int,
            transitionSource: DesktopModeTransitionSource,
            remoteTransition: RemoteTransition?,
        ) {
            executeRemoteCallWithTaskPermission(controller, "moveTaskToDesktop") { c ->
                c.moveTaskToDesktop(
                    taskId,
                    transitionSource = transitionSource,
                    remoteTransition = remoteTransition,
                )
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

        // Timeout used for CUJ_DESKTOP_MODE_ENTER_APP_HANDLE_DRAG_HOLD, this is longer than the
        // default timeout to avoid timing out in the middle of a drag action.
        private val APP_HANDLE_DRAG_HOLD_CUJ_TIMEOUT_MS: Long = TimeUnit.SECONDS.toMillis(10L)

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

    /** Defines interface for entering and exiting desktop windowing mode. */
    interface DesktopModeEntryExitTransitionListener {
        /** [transitionDuration] time it takes to run enter desktop mode transition */
        fun onEnterDesktopModeTransitionStarted(transitionDuration: Int)

        /** [transitionDuration] time it takes to run exit desktop mode transition */
        fun onExitDesktopModeTransitionStarted(transitionDuration: Int)
    }

    /** The positions on a screen that a task can snap to. */
    enum class SnapPosition {
        RIGHT,
        LEFT,
    }
}
