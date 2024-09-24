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
import android.util.Slog
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.WindowContainerTransaction
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.MODE_DEFAULT
import com.android.launcher3.icons.IconProvider
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition
import com.android.wm.shell.desktopmode.ReturnToDragStartAnimator
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility
import com.android.wm.shell.windowdecor.DragPositioningCallbackUtility.DragEventListener
import com.android.wm.shell.windowdecor.DragResizeWindowGeometry
import com.android.wm.shell.windowdecor.ResizeVeil
import java.util.function.Supplier

class DesktopTilingWindowDecoration(
    private val context: Context,
    private val syncQueue: SyncTransactionQueue,
    private val displayController: DisplayController,
    private val displayId: Int,
    private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    private val transitions: Transitions,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val toggleResizeDesktopTaskTransitionHandler: ToggleResizeDesktopTaskTransitionHandler,
    private val returnToDragStartAnimator: ReturnToDragStartAnimator,
    private val transactionSupplier: Supplier<Transaction> = Supplier { Transaction() },
) :
    Transitions.TransitionHandler,
    ShellTaskOrganizer.FocusListener,
    ShellTaskOrganizer.TaskVanishedListener,
    DragEventListener {
    companion object {
        private val TAG: String = DesktopTilingWindowDecoration::class.java.simpleName
        private const val TILING_DIVIDER_TAG = "Tiling Divider"
    }

    var leftTaskResizingHelper: AppResizingHelper? = null
    var rightTaskResizingHelper: AppResizingHelper? = null
    private var isTilingManagerInitialised = false
    private var desktopTilingDividerWindowManager: DesktopTilingDividerWindowManager? = null
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
                    isResizable = taskInfo.isResizeable,
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
                ?.updateDisabledResizingEdge(DragResizeWindowGeometry.DisabledEdge.RIGHT)
            rightTaskResizingHelper
                ?.desktopModeWindowDecoration
                ?.updateDisabledResizingEdge(DragResizeWindowGeometry.DisabledEdge.LEFT)
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
                )
            }
        // a leash to present the divider on top of, without re-parenting.
        val relativeLeash =
            leftTaskResizingHelper?.desktopModeWindowDecoration?.getLeash() ?: return tilingManager
        tilingManager?.generateViewHost(relativeLeash)
        return tilingManager
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
            val pm = context.getApplicationContext().getPackageManager()
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

    private fun isTilingFocusRemoved(taskInfo: RunningTaskInfo): Boolean {
        return taskInfo.isFocused &&
            isTilingFocused &&
            taskInfo.taskId != leftTaskResizingHelper?.taskInfo?.taskId &&
            taskInfo.taskId != rightTaskResizingHelper?.taskInfo?.taskId
    }

    private fun isTilingRefocused(taskInfo: RunningTaskInfo): Boolean {
        return !isTilingFocused &&
            taskInfo.isFocused &&
            (taskInfo.taskId == leftTaskResizingHelper?.taskInfo?.taskId ||
                taskInfo.taskId == rightTaskResizingHelper?.taskInfo?.taskId)
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

    fun moveTiledPairToFront(taskInfo: RunningTaskInfo): Boolean {
        if (!isTilingManagerInitialised) return false

        // If a task that isn't tiled is being focused, let the generic handler do the work.
        if (isTilingFocusRemoved(taskInfo)) {
            isTilingFocused = false
            return false
        }

        val leftTiledTask = leftTaskResizingHelper ?: return false
        val rightTiledTask = rightTaskResizingHelper ?: return false

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
