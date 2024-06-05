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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.IdRes
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.SurfaceControlViewHost
import android.view.View.OnClickListener
import android.view.View.OnGenericMotionListener
import android.view.View.OnTouchListener
import android.view.View.SCALE_Y
import android.view.View.TRANSLATION_Y
import android.view.View.TRANSLATION_Z
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.TaskConstants
import androidx.core.content.withStyledAttributes
import com.android.internal.R.attr.colorAccentPrimary
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer
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
        private val onGenericMotionListener: OnGenericMotionListener,
        private val onTouchListener: OnTouchListener,
        private val decorWindowContext: Context,
        private val menuPosition: PointF,
        private val transactionSupplier: Supplier<Transaction> = Supplier { Transaction() }
) {
    private var maximizeMenu: AdditionalViewHostViewContainer? = null
    private lateinit var viewHost: SurfaceControlViewHost
    private lateinit var leash: SurfaceControl
    private val openMenuAnimatorSet = AnimatorSet()
    private val cornerRadius = loadDimensionPixelSize(
            R.dimen.desktop_mode_maximize_menu_corner_radius
    ).toFloat()
    private val menuWidth = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_width)
    private val menuHeight = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_height)
    private val menuPadding = loadDimensionPixelSize(R.dimen.desktop_mode_menu_padding)

    private lateinit var snapRightButton: Button
    private lateinit var snapLeftButton: Button
    private lateinit var maximizeButton: Button
    private lateinit var maximizeButtonLayout: FrameLayout
    private lateinit var snapButtonsLayout: LinearLayout

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
        animateOpenMenu()
    }

    /** Closes the maximize window and releases its view. */
    fun close() {
        openMenuAnimatorSet.cancel()
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
                .setCornerRadius(leash, cornerRadius)
                .show(leash)
        maximizeMenu =
            AdditionalViewHostViewContainer(leash, viewHost, transactionSupplier)

        syncQueue.runInSync { transaction ->
            transaction.merge(t)
            t.close()
        }
    }

    private fun animateOpenMenu() {
        val maximizeMenuView = maximizeMenu?.view ?: return
        val maximizeWindowText = maximizeMenuView.requireViewById<TextView>(
                R.id.maximize_menu_maximize_window_text)
        val snapWindowText = maximizeMenuView.requireViewById<TextView>(
                R.id.maximize_menu_snap_window_text)

        openMenuAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(maximizeMenuView, SCALE_Y, STARTING_MENU_HEIGHT_SCALE, 1f)
                        .apply {
                            duration = MENU_HEIGHT_ANIMATION_DURATION_MS
                            interpolator = EMPHASIZED_DECELERATE
                        },
                ValueAnimator.ofFloat(STARTING_MENU_HEIGHT_SCALE, 1f)
                        .apply {
                            duration = MENU_HEIGHT_ANIMATION_DURATION_MS
                            interpolator = EMPHASIZED_DECELERATE
                            addUpdateListener {
                                // Animate padding so that controls stay pinned to the bottom of
                                // the menu.
                                val value = animatedValue as Float
                                val topPadding = menuPadding -
                                        ((1 - value) * menuHeight).toInt()
                                maximizeMenuView.setPadding(menuPadding, topPadding,
                                        menuPadding, menuPadding)
                            }
                        },
                ValueAnimator.ofFloat(1 / STARTING_MENU_HEIGHT_SCALE, 1f).apply {
                            duration = MENU_HEIGHT_ANIMATION_DURATION_MS
                            interpolator = EMPHASIZED_DECELERATE
                            addUpdateListener {
                                // Scale up the children of the maximize menu so that the menu
                                // scale is cancelled out and only the background is scaled.
                                val value = animatedValue as Float
                                maximizeButtonLayout.scaleY = value
                                snapButtonsLayout.scaleY = value
                                maximizeWindowText.scaleY = value
                                snapWindowText.scaleY = value
                            }
                        },
                ObjectAnimator.ofFloat(maximizeMenuView, TRANSLATION_Y,
                        (STARTING_MENU_HEIGHT_SCALE - 1) * menuHeight, 0f).apply {
                    duration = MENU_HEIGHT_ANIMATION_DURATION_MS
                    interpolator = EMPHASIZED_DECELERATE
                },
                ObjectAnimator.ofInt(maximizeMenuView.background, "alpha",
                        MAX_DRAWABLE_ALPHA_VALUE).apply {
                    duration = ALPHA_ANIMATION_DURATION_MS
                },
                ValueAnimator.ofFloat(0f, 1f)
                        .apply {
                            duration = ALPHA_ANIMATION_DURATION_MS
                            startDelay = CONTROLS_ALPHA_ANIMATION_DELAY_MS
                            addUpdateListener {
                                val value = animatedValue as Float
                                maximizeButtonLayout.alpha = value
                                snapButtonsLayout.alpha = value
                                maximizeWindowText.alpha = value
                                snapWindowText.alpha = value
                            }
                        },
                ObjectAnimator.ofFloat(maximizeMenuView, TRANSLATION_Z, MENU_Z_TRANSLATION)
                        .apply {
                            duration = ELEVATION_ANIMATION_DURATION_MS
                            startDelay = CONTROLS_ALPHA_ANIMATION_DELAY_MS
                        }
        )
        openMenuAnimatorSet.start()
    }

    private fun loadDimensionPixelSize(resourceId: Int): Int {
        return if (resourceId == Resources.ID_NULL) {
            0
        } else {
            decorWindowContext.resources.getDimensionPixelSize(resourceId)
        }
    }

    private fun setupMaximizeMenu() {
        val maximizeMenuView = maximizeMenu?.view ?: return

        maximizeMenuView.setOnGenericMotionListener(onGenericMotionListener)
        maximizeMenuView.setOnTouchListener(onTouchListener)

        maximizeButtonLayout = maximizeMenuView.requireViewById(
                R.id.maximize_menu_maximize_button_layout)

        maximizeButton = maximizeMenuView.requireViewById(R.id.maximize_menu_maximize_button)
        maximizeButton.setOnClickListener(onClickListener)
        maximizeButton.setOnGenericMotionListener(onGenericMotionListener)

        snapRightButton = maximizeMenuView.requireViewById(R.id.maximize_menu_snap_right_button)
        snapRightButton.setOnClickListener(onClickListener)
        snapRightButton.setOnGenericMotionListener(onGenericMotionListener)

        snapLeftButton = maximizeMenuView.requireViewById(R.id.maximize_menu_snap_left_button)
        snapLeftButton.setOnClickListener(onClickListener)
        snapLeftButton.setOnGenericMotionListener(onGenericMotionListener)

        snapButtonsLayout = maximizeMenuView.requireViewById(R.id.maximize_menu_snap_menu_layout)
        snapButtonsLayout.setOnGenericMotionListener(onGenericMotionListener)
    }

    /**
     * A valid menu input is one of the following:
     * An input that happens in the menu views.
     * Any input before the views have been laid out.
     *
     * @param inputPoint the input to compare against.
     */
    fun isValidMenuInput(ev: MotionEvent): Boolean {
        val x = ev.rawX
        val y = ev.rawY
        return !viewsLaidOut() || (menuPosition.x <= x && menuPosition.x + menuWidth >= x &&
                menuPosition.y <= y && menuPosition.y + menuHeight >= y)
    }

    /**
     * Check if the views for maximize menu can be seen.
     */
    private fun viewsLaidOut(): Boolean {
        return maximizeMenu?.view?.isLaidOut ?: false
    }

    fun onMaximizeMenuHoverEnter(viewId: Int, ev: MotionEvent) {
        setSnapButtonsColorOnHover(viewId, ev)
    }

    fun onMaximizeMenuHoverMove(viewId: Int, ev: MotionEvent) {
        setSnapButtonsColorOnHover(viewId, ev)
    }

    fun onMaximizeMenuHoverExit(id: Int, ev: MotionEvent) {
        val inSnapMenuBounds = ev.x >= 0 && ev.x <= snapButtonsLayout.width &&
                ev.y >= 0 && ev.y <= snapButtonsLayout.height
        val colorList = decorWindowContext.getColorStateList(
                R.color.desktop_mode_maximize_menu_button_color_selector)

        if (id == R.id.maximize_menu_maximize_button) {
            maximizeButton.background?.setTintList(colorList)
            maximizeButtonLayout.setBackgroundResource(
                    R.drawable.desktop_mode_maximize_menu_layout_background)
        } else if (id == R.id.maximize_menu_snap_menu_layout && !inSnapMenuBounds) {
            // After exiting the snap menu layout area, checks to see that user is not still
            // hovering within the snap menu layout bounds which would indicate that the user is
            // hovering over a snap button within the snap menu layout rather than having exited.
            snapLeftButton.background?.setTintList(colorList)
            snapLeftButton.background?.alpha = 255
            snapRightButton.background?.setTintList(colorList)
            snapRightButton.background?.alpha = 255
            snapButtonsLayout.setBackgroundResource(
                    R.drawable.desktop_mode_maximize_menu_layout_background)
        }
    }

    private fun setSnapButtonsColorOnHover(viewId: Int, ev: MotionEvent) {
        decorWindowContext.withStyledAttributes(null, intArrayOf(colorAccentPrimary), 0, 0) {
            val materialColor = getColor(0, 0)
            val snapMenuCenter = snapButtonsLayout.width / 2
            if (viewId == R.id.maximize_menu_maximize_button) {
                // Highlight snap maximize window button
                maximizeButton.background?.setTint(materialColor)
                maximizeButtonLayout.setBackgroundResource(
                        R.drawable.desktop_mode_maximize_menu_layout_background_on_hover)
            } else if (viewId == R.id.maximize_menu_snap_left_button ||
                    (viewId == R.id.maximize_menu_snap_menu_layout && ev.x <= snapMenuCenter)) {
                // Highlight snap left button
                snapRightButton.background?.setTint(materialColor)
                snapLeftButton.background?.setTint(materialColor)
                snapButtonsLayout.setBackgroundResource(
                        R.drawable.desktop_mode_maximize_menu_layout_background_on_hover)
                snapRightButton.background?.alpha = 102
                snapLeftButton.background?.alpha = 255
            } else if (viewId == R.id.maximize_menu_snap_right_button ||
                    (viewId == R.id.maximize_menu_snap_menu_layout && ev.x > snapMenuCenter)) {
                // Highlight snap right button
                snapRightButton.background?.setTint(materialColor)
                snapLeftButton.background?.setTint(materialColor)
                snapButtonsLayout.setBackgroundResource(
                        R.drawable.desktop_mode_maximize_menu_layout_background_on_hover)
                snapRightButton.background?.alpha = 255
                snapLeftButton.background?.alpha = 102
            }
        }
    }

    companion object {
        // Open menu animation constants
        private const val ALPHA_ANIMATION_DURATION_MS = 50L
        private const val MAX_DRAWABLE_ALPHA_VALUE = 255
        private const val STARTING_MENU_HEIGHT_SCALE = 0.8f
        private const val MENU_HEIGHT_ANIMATION_DURATION_MS = 300L
        private const val ELEVATION_ANIMATION_DURATION_MS = 50L
        private const val CONTROLS_ALPHA_ANIMATION_DELAY_MS = 33L
        private const val MENU_Z_TRANSLATION = 1f
        fun isMaximizeMenuView(@IdRes viewId: Int): Boolean {
            return viewId == R.id.maximize_menu ||
                    viewId == R.id.maximize_menu_maximize_button ||
                    viewId == R.id.maximize_menu_maximize_button_layout ||
                    viewId == R.id.maximize_menu_snap_left_button ||
                    viewId == R.id.maximize_menu_snap_right_button ||
                    viewId == R.id.maximize_menu_snap_menu_layout ||
                    viewId == R.id.maximize_menu_snap_menu_layout
        }
    }
}
