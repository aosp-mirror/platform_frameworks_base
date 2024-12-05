/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.windowdecor.tiling

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.IBinder
import android.os.UserHandle
import android.util.Slog
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.internal.annotations.VisibleForTesting
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.MODE_DEFAULT
import com.android.launcher3.icons.IconProvider
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopModeEventLogger
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopRepository
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.ReturnToDragStartAnimator
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TRANSIT_MINIMIZE
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility.DragEventListener
import com.android.wm.shell.windowdecor.DragResizeWindowGeometry
import com.android.wm.shell.windowdecor.DragResizeWindowGeometry.DisabledEdge.NONE
import com.android.wm.shell.windowdecor.ResizeVeil
import com.android.wm.shell.windowdecor.extension.isFullscreen
import java.util.function.Supplier

class DesktopTilingWindowDecoration(
    private var context: Context,
    private val syncQueue: SyncTransactionQueue,
    private val displayController: DisplayController,
    private val displayId: Int,
    private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    private val transitions: Transitions,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler,
    private val returnToDragStartAnimator: ReturnToDragStartAnimator,
    private val desktopUserRepositories: DesktopUserRepositories,
    private val desktopModeEventLogger: DesktopModeEventLogger,
    private val transactionSupplier: Supplier<Transaction> = Supplier { Transaction() },
) :
    Transitions.TransitionHandler,
    ShellTaskOrganizer.FocusListener,
    ShellTaskOrganizer.TaskVanishedListener,
    DragEventListener,
    Transitions.TransitionObserver {
    companion object {
        private val TAG: String = DesktopTilingWindowDecoration::class.java.simpleName
        private const val TILING_DIVIDER_TAG = "Tiling Divider"
    }

    var leftTaskResizingHelper: AppResizingHelper? = null
    var rightTaskResizingHelper: AppResizingHelper? = null
    private var isTilingManagerInitialised = false
    @VisibleForTesting
    var desktopTilingDividerWindowManager: DesktopTilingDividerWindowManager? = null
    private lateinit var dividerBounds: Rect
    private var isResizing = false
    private var isTilingFocused = false

    fun onAppTiled(
        taskInfo: RunningTaskInfo,
        desktopModeWindowDecoration: DesktopModeWindowDecoration,
        position: SnapPosition,
        currentBounds: Rect,
    ): Boolean {
        val destinationBounds = getSnapBounds(taskInfo, position)
        val resizeMetadata =
            AppResizingHelper(
                taskInfo,
                desktopModeWindowDecoration,
                context,
                destinationBounds,
                displayController,
                transactionSupplier,
            )
        val isFirstTiledApp = leftTaskResizingHelper == null && rightTaskResizingHelper == null
        val isTiled = destinationBounds != taskInfo.configuration.windowConfiguration.bounds

        initTilingApps(resizeMetadata, position, taskInfo)
        // Observe drag resizing to break tiling if a task is drag resized.
        desktopModeWindowDecoration.addDragResizeListener(this)

        if (isTiled) {
            val wct = WindowContainerTransaction().setBounds(taskInfo.token, destinationBounds)
            toggleResizeDesktopTaskTransitionHandler.startTransition(wct, currentBounds)
        } else {
            // Handle the case where we attempt to snap resize when already snap resized: the task
            // position won't need to change but we want to animate the surface going back to the
            // snapped position from the "dragged-to-the-edge" position.
            if (destinationBounds != currentBounds) {
                returnToDragStartAnimator.start(
                    taskInfo.taskId,
                    resizeMetadata.getLeash(),
                    startBounds = currentBounds,
                    endBounds = destinationBounds,
                )
            }
        }
        initTilingForDisplayIfNeeded(taskInfo.configuration, isFirstTiledApp)
        return isTiled
    }

    // If a task is already tiled on the same position, release this task, otherwise if the same
    // task is tiled on the opposite side, remove it from the opposite side so it's tiled correctly.
    private fun initTilingApps(
        taskResizingHelper: AppResizingHelper,
        position: SnapPosition,
        taskInfo: RunningTaskInfo,
    ) {
        when (position) {
            SnapPosition.RIGHT -> {
                rightTaskResizingHelper?.let { removeTaskIfTiled(it.taskInfo.taskId) }
                if (leftTaskResizingHelper?.taskInfo?.taskId == taskInfo.taskId) {
                    removeTaskIfTiled(taskInfo.taskId)
                }
                rightTaskResizingHelper = taskResizingHelper
            }

            SnapPosition.LEFT -> {
                leftTaskResizingHelper?.let { removeTaskIfTiled(it.taskInfo.taskId) }
                if (taskInfo.taskId == rightTaskResizingHelper?.taskInfo?.taskId) {
                    removeTaskIfTiled(taskInfo.taskId)
                }
                leftTaskResizingHelper = taskResizingHelper
            }
        }
    }

    private fun initTilingForDisplayIfNeeded(config: Configuration, firstTiledApp: Boolean) {
        if (leftTaskResizingHelper != null && rightTaskResizingHelper != null) {
            if (!isTilingManagerInitialised) {
                desktopTilingDividerWindowManager = initTilingManagerForDisplay(displayId, config)
                isTilingManagerInitialised = true
                shellTaskOrganizer.addFocusListener(this)
                isTilingFocused = true
            }
            leftTaskResizingHelper?.initIfNeeded()
            rightTaskResizingHelper?.initIfNeeded()
            leftTaskResizingHelper
                ?.desktopModeWindowDecoration
                ?.updateDisabledResizingEdge(
                    DragResizeWindowGeometry.DisabledEdge.RIGHT,
                    /* shouldDelayUpdate = */ false,
                )
            rightTaskResizingHelper
                ?.desktopModeWindowDecoration
                ?.updateDisabledResizingEdge(
                    DragResizeWindowGeometry.DisabledEdge.LEFT,
                    /* shouldDelayUpdate = */ false,
                )
        } else if (firstTiledApp) {
            shellTaskOrganizer.addTaskVanishedListener(this)
        }
    }

    private fun initTilingManagerForDisplay(
        displayId: Int,
        config: Configuration,
    ): DesktopTilingDividerWindowManager? {
        val displayLayout = displayController.getDisplayLayout(displayId)
        val builder = SurfaceControl.Builder()
        rootTdaOrganizer.attachToDisplayArea(displayId, builder)
        val leash = builder.setName(TILING_DIVIDER_TAG).setContainerLayer().build()
        val displayContext = displayController.getDisplayContext(displayId) ?: return null
        val tilingManager =
            displayLayout?.let {
                dividerBounds = inflateDividerBounds(it)
                DesktopTilingDividerWindowManager(
                    config,
                    TAG,
                    context,
                    leash,
                    syncQueue,
                    this,
                    transactionSupplier,
                    dividerBounds,
                    displayContext,
                )
            }
        // a leash to present the divider on top of, without re-parenting.
        val relativeLeash =
            leftTaskResizingHelper?.desktopModeWindowDecoration?.getLeash() ?: return tilingManager
        tilingManager?.generateViewHost(relativeLeash)
        return tilingManager
    }

    fun onDividerHandleDragStart(motionEvent: MotionEvent) {
        val leftTiledTask = leftTaskResizingHelper ?: return
        val rightTiledTask = rightTaskResizingHelper ?: return
        val inputMethod = DesktopModeEventLogger.getInputMethodFromMotionEvent(motionEvent)

        desktopModeEventLogger.logTaskResizingStarted(
            ResizeTrigger.TILING_DIVIDER,
            inputMethod,
            leftTiledTask.taskInfo,
            leftTiledTask.bounds.width(),
            leftTiledTask.bounds.height(),
            displayController,
        )

        desktopModeEventLogger.logTaskResizingStarted(
            ResizeTrigger.TILING_DIVIDER,
            inputMethod,
            rightTiledTask.taskInfo,
            rightTiledTask.bounds.width(),
            rightTiledTask.bounds.height(),
            displayController,
        )
    }

    fun onDividerHandleMoved(dividerBounds: Rect, t: SurfaceControl.Transaction): Boolean {
        val leftTiledTask = leftTaskResizingHelper ?: return false
        val rightTiledTask = rightTaskResizingHelper ?: return false
        val stableBounds = Rect()
        val displayLayout = displayController.getDisplayLayout(displayId)
        displayLayout?.getStableBounds(stableBounds)

        if (stableBounds.isEmpty) return false

        val leftBounds = leftTiledTask.bounds
        val rightBounds = rightTiledTask.bounds
        val newLeftBounds =
            Rect(leftBounds.left, leftBounds.top, dividerBounds.left, leftBounds.bottom)
        val newRightBounds =
            Rect(dividerBounds.right, rightBounds.top, rightBounds.right, rightBounds.bottom)

        // If one of the apps is getting smaller or bigger than size constraint, ignore finger move.
        if (
            isResizeWithinSizeConstraints(
                newLeftBounds,
                newRightBounds,
                leftBounds,
                rightBounds,
                stableBounds,
            )
        ) {
            return false
        }

        // The final new bounds for each app has to be registered to make sure a startAnimate
        // when the new bounds are different from old bounds, otherwise hide the veil without
        // waiting for an animation as no animation will run when no bounds are changed.
        leftTiledTask.newBounds.set(newLeftBounds)
        rightTiledTask.newBounds.set(newRightBounds)
        if (!isResizing) {
            leftTiledTask.showVeil(t)
            rightTiledTask.showVeil(t)
            isResizing = true
        } else {
            leftTiledTask.updateVeil(t)
            rightTiledTask.updateVeil(t)
        }

        // Applies showing/updating veil for both apps and moving the divider into its new position.
        t.apply()
        return true
    }

    fun onDividerHandleDragEnd(
        dividerBounds: Rect,
        t: SurfaceControl.Transaction,
        motionEvent: MotionEvent,
    ) {
        val leftTiledTask = leftTaskResizingHelper ?: return
        val rightTiledTask = rightTaskResizingHelper ?: return
        val inputMethod = DesktopModeEventLogger.getInputMethodFromMotionEvent(motionEvent)

        desktopModeEventLogger.logTaskResizingEnded(
            ResizeTrigger.TILING_DIVIDER,
            inputMethod,
            leftTiledTask.taskInfo,
            leftTiledTask.newBounds.width(),
            leftTiledTask.newBounds.height(),
            displayController,
        )

        desktopModeEventLogger.logTaskResizingEnded(
            ResizeTrigger.TILING_DIVIDER,
            inputMethod,
            rightTiledTask.taskInfo,
            rightTiledTask.newBounds.width(),
            rightTiledTask.newBounds.height(),
            displayController,
        )

        if (leftTiledTask.newBounds == leftTiledTask.bounds) {
            leftTiledTask.hideVeil()
            rightTiledTask.hideVeil()
            isResizing = false
            return
        }
        leftTiledTask.bounds.set(leftTiledTask.newBounds)
        rightTiledTask.bounds.set(rightTiledTask.newBounds)
        onDividerHandleMoved(dividerBounds, t)
        isResizing = false
        val wct = WindowContainerTransaction()
        wct.setBounds(leftTiledTask.taskInfo.token, leftTiledTask.bounds)
        wct.setBounds(rightTiledTask.taskInfo.token, rightTiledTask.bounds)
        transitions.startTransition(TRANSIT_CHANGE, wct, this)
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: Transaction,
        finishTransaction: Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        val leftTiledTask = leftTaskResizingHelper ?: return false
        val rightTiledTask = rightTaskResizingHelper ?: return false
        for (change in info.getChanges()) {
            val sc: SurfaceControl = change.getLeash()
            val endBounds =
                if (change.taskInfo?.taskId == leftTiledTask.taskInfo.taskId) {
                    leftTiledTask.bounds
                } else {
                    rightTiledTask.bounds
                }
            startTransaction.setWindowCrop(sc, endBounds.width(), endBounds.height())
            finishTransaction.setWindowCrop(sc, endBounds.width(), endBounds.height())
        }

        startTransaction.apply()
        leftTiledTask.hideVeil()
        rightTiledTask.hideVeil()
        finishCallback.onTransitionFinished(null)
        return true
    }

    // TODO(b/361505243) bring tasks to front here when the empty request info bug is fixed.
    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo,
    ): WindowContainerTransaction? {
        return null
    }

    override fun onDragStart(taskId: Int) {}

    override fun onDragMove(taskId: Int) {
        removeTaskIfTiled(taskId)
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: Transaction,
        finishTransaction: Transaction,
    ) {
        for (change in info.changes) {
            change.taskInfo?.let {
                if (it.isFullscreen || isMinimized(change.mode, info.type)) {
                    removeTaskIfTiled(it.taskId, /* taskVanished= */ false, it.isFullscreen)
                }
            }
        }
    }

    private fun isMinimized(changeMode: Int, infoType: Int): Boolean {
        return (changeMode == TRANSIT_TO_BACK &&
            (infoType == TRANSIT_MINIMIZE ||
                infoType == TRANSIT_TO_BACK ||
                infoType == TRANSIT_OPEN))
    }

    class AppResizingHelper(
        val taskInfo: RunningTaskInfo,
        val desktopModeWindowDecoration: DesktopModeWindowDecoration,
        val context: Context,
        val bounds: Rect,
        val displayController: DisplayController,
        val transactionSupplier: Supplier<Transaction>,
    ) {
        var isInitialised = false
        var newBounds = Rect(bounds)
        private lateinit var resizeVeilBitmap: Bitmap
        private lateinit var resizeVeil: ResizeVeil
        private val displayContext = displayController.getDisplayContext(taskInfo.displayId)
        private val userContext =
            context.createContextAsUser(UserHandle.of(taskInfo.userId), /* flags= */ 0)

        fun initIfNeeded() {
            if (!isInitialised) {
                initVeil()
                isInitialised = true
            }
        }

        private fun initVeil() {
            val baseActivity = taskInfo.baseActivity
            if (baseActivity == null) {
                Slog.e(TAG, "Base activity component not found in task")
                return
            }
            val resizeVeilIconFactory =
                displayContext?.let {
                    createIconFactory(displayContext, R.dimen.desktop_mode_resize_veil_icon_size)
                } ?: return
            val pm = userContext.getPackageManager()
            val activityInfo = pm.getActivityInfo(baseActivity, 0 /* flags */)
            val provider = IconProvider(displayContext)
            val appIconDrawable = provider.getIcon(activityInfo)
            resizeVeilBitmap =
                resizeVeilIconFactory.createScaledBitmap(appIconDrawable, MODE_DEFAULT)
            resizeVeil =
                ResizeVeil(
                    context = displayContext,
                    displayController = displayController,
                    appIcon = resizeVeilBitmap,
                    parentSurface = desktopModeWindowDecoration.getLeash(),
                    surfaceControlTransactionSupplier = transactionSupplier,
                    taskInfo = taskInfo,
                )
        }

        fun showVeil(t: Transaction) =
            resizeVeil.updateTransactionWithShowVeil(
                t,
                desktopModeWindowDecoration.getLeash(),
                bounds,
                taskInfo,
            )

        fun updateVeil(t: Transaction) = resizeVeil.updateTransactionWithResizeVeil(t, newBounds)

        fun hideVeil() = resizeVeil.hideVeil()

        private fun createIconFactory(context: Context, dimensions: Int): BaseIconFactory {
            val resources: Resources = context.resources
            val densityDpi: Int = resources.getDisplayMetrics().densityDpi
            val iconSize: Int = resources.getDimensionPixelSize(dimensions)
            return BaseIconFactory(context, densityDpi, iconSize)
        }

        fun getLeash(): SurfaceControl = desktopModeWindowDecoration.getLeash()

        fun dispose() {
            if (isInitialised) resizeVeil.dispose()
        }
    }

    // Only called if [taskInfo] relates to a focused task
    private fun isTilingFocusRemoved(taskInfo: RunningTaskInfo): Boolean {
        return isTilingFocused &&
            taskInfo.taskId != leftTaskResizingHelper?.taskInfo?.taskId &&
            taskInfo.taskId != rightTaskResizingHelper?.taskInfo?.taskId
    }

    override fun onFocusTaskChanged(taskInfo: RunningTaskInfo?) {
        if (taskInfo != null) {
            moveTiledPairToFront(taskInfo)
        }
    }

    // Only called if [taskInfo] relates to a focused task
    private fun isTilingRefocused(taskInfo: RunningTaskInfo): Boolean {
        return taskInfo.taskId == leftTaskResizingHelper?.taskInfo?.taskId ||
                taskInfo.taskId == rightTaskResizingHelper?.taskInfo?.taskId
    }

    private fun buildTiledTasksMoveToFront(leftOnTop: Boolean): WindowContainerTransaction {
        val wct = WindowContainerTransaction()
        val leftTiledTask = leftTaskResizingHelper ?: return wct
        val rightTiledTask = rightTaskResizingHelper ?: return wct
        if (leftOnTop) {
            wct.reorder(rightTiledTask.taskInfo.token, true)
            wct.reorder(leftTiledTask.taskInfo.token, true)
        } else {
            wct.reorder(leftTiledTask.taskInfo.token, true)
            wct.reorder(rightTiledTask.taskInfo.token, true)
        }
        return wct
    }

    fun removeTaskIfTiled(
        taskId: Int,
        taskVanished: Boolean = false,
        shouldDelayUpdate: Boolean = false,
    ) {
        if (taskId == leftTaskResizingHelper?.taskInfo?.taskId) {
            removeTask(leftTaskResizingHelper, taskVanished, shouldDelayUpdate)
            leftTaskResizingHelper = null
            rightTaskResizingHelper
                ?.desktopModeWindowDecoration
                ?.updateDisabledResizingEdge(NONE, shouldDelayUpdate)
            tearDownTiling()
            return
        }

        if (taskId == rightTaskResizingHelper?.taskInfo?.taskId) {
            removeTask(rightTaskResizingHelper, taskVanished, shouldDelayUpdate)
            rightTaskResizingHelper = null
            leftTaskResizingHelper
                ?.desktopModeWindowDecoration
                ?.updateDisabledResizingEdge(NONE, shouldDelayUpdate)
            tearDownTiling()
        }
    }

    fun resetTilingSession() {
        if (leftTaskResizingHelper != null) {
            removeTask(leftTaskResizingHelper, taskVanished = false, shouldDelayUpdate = true)
            leftTaskResizingHelper = null
        }
        if (rightTaskResizingHelper != null) {
            removeTask(rightTaskResizingHelper, taskVanished = false, shouldDelayUpdate = true)
            rightTaskResizingHelper = null
        }
        tearDownTiling()
    }

    private fun removeTask(
        appResizingHelper: AppResizingHelper?,
        taskVanished: Boolean = false,
        shouldDelayUpdate: Boolean,
    ) {
        if (appResizingHelper == null) return
        if (!taskVanished) {
            appResizingHelper.desktopModeWindowDecoration.removeDragResizeListener(this)
            appResizingHelper.desktopModeWindowDecoration.updateDisabledResizingEdge(
                NONE,
                shouldDelayUpdate,
            )
        }
        appResizingHelper.dispose()
    }

    fun onOverviewAnimationStateChange(isRunning: Boolean) {
        if (!isTilingManagerInitialised) return

        if (isRunning) {
            desktopTilingDividerWindowManager?.hideDividerBar()
        } else if (allTiledTasksVisible()) {
            desktopTilingDividerWindowManager?.showDividerBar()
        }
    }

    override fun onTaskVanished(taskInfo: RunningTaskInfo?) {
        val taskId = taskInfo?.taskId ?: return
        removeTaskIfTiled(taskId, taskVanished = true, shouldDelayUpdate = true)
    }

    /**
     * Moves the tiled pair to the front of the task stack, if the [taskInfo] is focused and one of
     * the two tiled tasks.
     *
     * If specified, [isTaskFocused] will override [RunningTaskInfo.isFocused]. This is to be used
     * when called when the task will be focused, but the [taskInfo] hasn't been updated yet.
     */
    fun moveTiledPairToFront(taskInfo: RunningTaskInfo, isTaskFocused: Boolean? = null): Boolean {
        if (!isTilingManagerInitialised) return false

        val isFocused = isTaskFocused ?: taskInfo.isFocused
        if (!isFocused) return false

        // If a task that isn't tiled is being focused, let the generic handler do the work.
        if (isTilingFocusRemoved(taskInfo)) {
            isTilingFocused = false
            return false
        }

        val leftTiledTask = leftTaskResizingHelper ?: return false
        val rightTiledTask = rightTaskResizingHelper ?: return false
        if (!allTiledTasksVisible()) return false
        val isLeftOnTop = taskInfo.taskId == leftTiledTask.taskInfo.taskId
        if (isTilingRefocused(taskInfo)) {
            val t = transactionSupplier.get()
            isTilingFocused = true
            if (taskInfo.taskId == leftTaskResizingHelper?.taskInfo?.taskId) {
                desktopTilingDividerWindowManager?.onRelativeLeashChanged(
                    leftTiledTask.getLeash(),
                    t,
                )
            }
            if (taskInfo.taskId == rightTaskResizingHelper?.taskInfo?.taskId) {
                desktopTilingDividerWindowManager?.onRelativeLeashChanged(
                    rightTiledTask.getLeash(),
                    t,
                )
            }
            transitions.startTransition(
                TRANSIT_TO_FRONT,
                buildTiledTasksMoveToFront(isLeftOnTop),
                null,
            )
            t.apply()
            return true
        }
        return false
    }

    private fun allTiledTasksVisible(): Boolean {
        val leftTiledTask = leftTaskResizingHelper ?: return false
        val rightTiledTask = rightTaskResizingHelper ?: return false
        val taskRepository = desktopUserRepositories.current
        return taskRepository.isVisibleTask(leftTiledTask.taskInfo.taskId) &&
            taskRepository.isVisibleTask(rightTiledTask.taskInfo.taskId)
    }

    private fun isResizeWithinSizeConstraints(
        newLeftBounds: Rect,
        newRightBounds: Rect,
        leftBounds: Rect,
        rightBounds: Rect,
        stableBounds: Rect,
    ): Boolean {
        return DragPositioningCallbackUtility.isExceedingWidthConstraint(
            newLeftBounds.width(),
            leftBounds.width(),
            stableBounds,
            displayController,
            leftTaskResizingHelper?.desktopModeWindowDecoration,
        ) ||
            DragPositioningCallbackUtility.isExceedingWidthConstraint(
                newRightBounds.width(),
                rightBounds.width(),
                stableBounds,
                displayController,
                rightTaskResizingHelper?.desktopModeWindowDecoration,
            )
    }

    private fun getSnapBounds(taskInfo: RunningTaskInfo, position: SnapPosition): Rect {
        val displayLayout = displayController.getDisplayLayout(taskInfo.displayId) ?: return Rect()

        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)
        val leftTiledTask = leftTaskResizingHelper
        val rightTiledTask = rightTaskResizingHelper
        val destinationWidth = stableBounds.width() / 2
        return when (position) {
            SnapPosition.LEFT -> {
                val rightBound =
                    if (rightTiledTask == null) {
                        stableBounds.left + destinationWidth -
                            context.resources.getDimensionPixelSize(
                                R.dimen.split_divider_bar_width
                            ) / 2
                    } else {
                        rightTiledTask.bounds.left -
                            context.resources.getDimensionPixelSize(R.dimen.split_divider_bar_width)
                    }
                Rect(stableBounds.left, stableBounds.top, rightBound, stableBounds.bottom)
            }

            SnapPosition.RIGHT -> {
                val leftBound =
                    if (leftTiledTask == null) {
                        stableBounds.right - destinationWidth +
                            context.resources.getDimensionPixelSize(
                                R.dimen.split_divider_bar_width
                            ) / 2
                    } else {
                        leftTiledTask.bounds.right +
                            context.resources.getDimensionPixelSize(R.dimen.split_divider_bar_width)
                    }
                Rect(leftBound, stableBounds.top, stableBounds.right, stableBounds.bottom)
            }
        }
    }

    private fun inflateDividerBounds(displayLayout: DisplayLayout): Rect {
        val stableBounds = Rect()
        displayLayout.getStableBounds(stableBounds)

        val leftDividerBounds = leftTaskResizingHelper?.bounds?.right ?: return Rect()
        val rightDividerBounds = rightTaskResizingHelper?.bounds?.left ?: return Rect()

        // Bounds should never be null here, so assertion is necessary otherwise it's illegal state.
        return Rect(leftDividerBounds, stableBounds.top, rightDividerBounds, stableBounds.bottom)
    }

    private fun tearDownTiling() {
        if (isTilingManagerInitialised) shellTaskOrganizer.removeFocusListener(this)

        if (leftTaskResizingHelper == null && rightTaskResizingHelper == null) {
            shellTaskOrganizer.removeTaskVanishedListener(this)
        }
        isTilingFocused = false
        isTilingManagerInitialised = false
        desktopTilingDividerWindowManager?.release()
        desktopTilingDividerWindowManager = null
    }
}
