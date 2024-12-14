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

package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.WindowManager
import android.window.TaskSnapshot
import androidx.compose.ui.graphics.toArgb
import com.android.wm.shell.shared.desktopmode.ManageWindowsViewContainer
import com.android.wm.shell.shared.split.SplitScreenConstants
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.extension.isFullscreen
import com.android.wm.shell.windowdecor.extension.isMultiWindow

/**
 * Implementation of [ManageWindowsViewContainer] meant to be used in desktop header and app
 * handle.
 */
class DesktopHandleManageWindowsMenu(
    private val callerTaskInfo: RunningTaskInfo,
    private val splitScreenController: SplitScreenController,
    private val captionX: Int,
    private val captionWidth: Int,
    private val windowManagerWrapper: WindowManagerWrapper,
    context: Context,
    snapshotList: List<Pair<Int, TaskSnapshot>>,
    onIconClickListener: ((Int) -> Unit),
    onOutsideClickListener: (() -> Unit)
) : ManageWindowsViewContainer(
    context,
    DecorThemeUtil(context).getColorScheme(callerTaskInfo).background.toArgb()
) {
    private var menuViewContainer: AdditionalViewContainer? = null

    init {
        show(snapshotList, onIconClickListener, onOutsideClickListener)
    }

    override fun close() {
        menuViewContainer?.releaseView()
    }

    private fun calculateMenuPosition(): Point {
        val position = Point()
        val nonFreeformX = (captionX + (captionWidth / 2) - (menuView.menuWidth / 2))
        when {
            callerTaskInfo.isFreeform -> {
                val taskBounds = callerTaskInfo.getConfiguration().windowConfiguration.bounds
                position.set(taskBounds.left, taskBounds.top)
            }
            callerTaskInfo.isFullscreen -> {
                position.set(nonFreeformX, 0)
            }
            callerTaskInfo.isMultiWindow -> {
                val splitPosition = splitScreenController.getSplitPosition(callerTaskInfo.taskId)
                val leftOrTopStageBounds = Rect()
                val rightOrBottomStageBounds = Rect()
                splitScreenController.getStageBounds(leftOrTopStageBounds, rightOrBottomStageBounds)
                // TODO(b/343561161): This needs to be calculated differently if the task is in
                //  top/bottom split.
                when (splitPosition) {
                    SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT -> {
                        position.set(leftOrTopStageBounds.width() + nonFreeformX, /* y= */ 0)
                    }

                    SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT -> {
                        position.set(nonFreeformX, /* y= */ 0)
                    }
                }
            }
        }
        return position
    }

    override fun addToContainer(menuView: ManageWindowsView) {
        val menuPosition = calculateMenuPosition()
        menuViewContainer = AdditionalSystemViewContainer(
            windowManagerWrapper,
            callerTaskInfo.taskId,
            menuPosition.x,
            menuPosition.y,
            menuView.menuWidth,
            menuView.menuHeight,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            menuView.rootView
        )
    }
}
