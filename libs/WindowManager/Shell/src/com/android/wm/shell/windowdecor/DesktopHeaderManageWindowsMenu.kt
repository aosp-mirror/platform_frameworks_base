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
import android.graphics.PixelFormat
import android.graphics.Point
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.window.TaskConstants
import android.window.TaskSnapshot
import androidx.compose.ui.graphics.toArgb
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.shared.desktopmode.ManageWindowsViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import java.util.function.Supplier

/**
 * Implementation of [ManageWindowsViewContainer] meant to be used in desktop header and app
 * handle.
 */
class DesktopHeaderManageWindowsMenu(
    private val callerTaskInfo: RunningTaskInfo,
    private val displayController: DisplayController,
    private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    context: Context,
    private val surfaceControlBuilderSupplier: Supplier<SurfaceControl.Builder>,
    private val surfaceControlTransactionSupplier: Supplier<SurfaceControl.Transaction>,
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

    override fun addToContainer(menuView: ManageWindowsView) {
        val taskBounds = callerTaskInfo.getConfiguration().windowConfiguration.bounds
        val menuPosition = Point(taskBounds.left, taskBounds.top)
        val builder = surfaceControlBuilderSupplier.get()
        rootTdaOrganizer.attachToDisplayArea(callerTaskInfo.displayId, builder)
        val leash = builder
            .setName("Manage Windows Menu")
            .setContainerLayer()
            .build()
        val lp = WindowManager.LayoutParams(
            menuView.menuWidth, menuView.menuHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT
        )
        val windowManager = WindowlessWindowManager(
            callerTaskInfo.configuration,
            leash,
            null // HostInputToken
        )
        val viewHost = SurfaceControlViewHost(
            context,
            displayController.getDisplay(callerTaskInfo.displayId), windowManager,
            "MaximizeMenu"
        )
        menuView.let { viewHost.setView(it.rootView, lp) }
        val t = surfaceControlTransactionSupplier.get()
        t.setLayer(leash, TaskConstants.TASK_CHILD_LAYER_FLOATING_MENU)
            .setPosition(leash, menuPosition.x.toFloat(), menuPosition.y.toFloat())
            .show(leash)
        t.apply()
        menuViewContainer = AdditionalViewHostViewContainer(
            leash, viewHost,
            surfaceControlTransactionSupplier
        )
    }
}
