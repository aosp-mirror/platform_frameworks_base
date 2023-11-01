/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.Button
import android.window.TaskConstants
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.windowdecor.WindowDecoration.AdditionalWindow
import java.util.function.Supplier


/**
 *  Menu that appears when user long clicks the maximize button. Gives the user the option to
 *  maximize the task or snap the task to the right or left half of the screen.
 */
class MaximizeMenu(
        private val syncQueue: SyncTransactionQueue,
        private val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
        private val displayController: DisplayController,
        private val taskInfo: RunningTaskInfo,
        private val onClickListener: OnClickListener,
        private val decorWindowContext: Context,
        private val menuPosition: PointF,
        private val transactionSupplier: Supplier<Transaction> = Supplier { Transaction() }
) {
    private var maximizeMenu: AdditionalWindow? = null
    private lateinit var viewHost: SurfaceControlViewHost
    private lateinit var leash: SurfaceControl
    private val shadowRadius = loadDimensionPixelSize(
            R.dimen.desktop_mode_maximize_menu_shadow_radius
    ).toFloat()
    private val cornerRadius = loadDimensionPixelSize(
            R.dimen.desktop_mode_maximize_menu_corner_radius
    ).toFloat()

    /** Position the menu relative to the caption's position. */
    fun positionMenu(position: PointF, t: Transaction) {
        menuPosition.set(position)
        t.setPosition(leash, menuPosition.x, menuPosition.y)
    }

    /** Creates and shows the maximize window. */
    fun show() {
        if (maximizeMenu != null) return
        createMaximizeMenu()
        setupMaximizeMenu()
    }

    /** Closes the maximize window and releases its view. */
    fun close() {
        maximizeMenu?.releaseView()
        maximizeMenu = null
    }

    /** Create a maximize menu that is attached to the display area. */
    private fun createMaximizeMenu() {
        val t = transactionSupplier.get()
        val v = LayoutInflater.from(decorWindowContext).inflate(
                R.layout.desktop_mode_window_decor_maximize_menu,
                null // Root
        )
        val builder = SurfaceControl.Builder()
        rootTdaOrganizer.attachToDisplayArea(taskInfo.displayId, builder)
        leash = builder
                .setName("Maximize Menu")
                .setContainerLayer()
                .build()
        val menuWidth = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_width)
        val menuHeight = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_height)
        val lp = WindowManager.LayoutParams(
                menuWidth,
                menuHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT
        )
        lp.title = "Maximize Menu for Task=" + taskInfo.taskId
        lp.setTrustedOverlay()
        val windowManager = WindowlessWindowManager(
                taskInfo.configuration,
                leash,
                null // HostInputToken
        )
        viewHost = SurfaceControlViewHost(decorWindowContext,
                displayController.getDisplay(taskInfo.displayId), windowManager,
                "MaximizeMenu")
        viewHost.setView(v, lp)

        // Bring menu to front when open
        t.setLayer(leash, TaskConstants.TASK_CHILD_LAYER_FLOATING_MENU)
                .setPosition(leash, menuPosition.x, menuPosition.y)
                .setWindowCrop(leash, menuWidth, menuHeight)
                .setShadowRadius(leash, shadowRadius)
                .setCornerRadius(leash, cornerRadius)
                .show(leash)
        maximizeMenu = AdditionalWindow(leash, viewHost, transactionSupplier)

        syncQueue.runInSync { transaction ->
            transaction.merge(t)
            t.close()
        }
    }

    private fun loadDimensionPixelSize(resourceId: Int): Int {
        return if (resourceId == Resources.ID_NULL) {
            0
        } else {
            decorWindowContext.resources.getDimensionPixelSize(resourceId)
        }
    }

    private fun setupMaximizeMenu() {
        val maximizeMenuView = maximizeMenu?.mWindowViewHost?.view ?: return

        maximizeMenuView.requireViewById<Button>(
                R.id.maximize_menu_maximize_button
        ).setOnClickListener(onClickListener)
        maximizeMenuView.requireViewById<Button>(
                R.id.maximize_menu_snap_right_button
        ).setOnClickListener(onClickListener)
        maximizeMenuView.requireViewById<Button>(
                R.id.maximize_menu_snap_left_button
        ).setOnClickListener(onClickListener)
    }

    /**
     * A valid menu input is one of the following:
     * An input that happens in the menu views.
     * Any input before the views have been laid out.
     *
     * @param inputPoint the input to compare against.
     */
    fun isValidMenuInput(inputPoint: PointF): Boolean {
        val menuView = maximizeMenu?.mWindowViewHost?.view ?: return true
        return !viewsLaidOut() || pointInView(menuView, inputPoint.x - menuPosition.x,
                inputPoint.y - menuPosition.y)
    }

    private fun pointInView(v: View, x: Float, y: Float): Boolean {
        return v.left <= x && v.right >= x && v.top <= y && v.bottom >= y
    }

    /**
     * Check if the views for maximize menu can be seen.
     */
    private fun viewsLaidOut(): Boolean {
        return maximizeMenu?.mWindowViewHost?.view?.isLaidOut ?: false
    }
}
