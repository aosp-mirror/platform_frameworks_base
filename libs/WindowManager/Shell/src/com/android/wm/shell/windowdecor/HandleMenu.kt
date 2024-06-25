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

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.SurfaceSyncGroup
import androidx.annotation.VisibleForTesting
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.split.SplitScreenConstants
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer
import com.android.wm.shell.windowdecor.extension.isFullscreen

/**
 * Handle menu opened when the appropriate button is clicked on.
 *
 * Displays up to 3 pills that show the following:
 * App Info: App name, app icon, and collapse button to close the menu.
 * Windowing Options(Proto 2 only): Buttons to change windowing modes.
 * Additional Options: Miscellaneous functions including screenshot and closing task.
 */
class HandleMenu(
    private val parentDecor: DesktopModeWindowDecoration,
    private val layoutResId: Int,
    private val onClickListener: View.OnClickListener?,
    private val onTouchListener: View.OnTouchListener?,
    private val appIconBitmap: Bitmap?,
    private val appName: CharSequence?,
    private val displayController: DisplayController,
    private val splitScreenController: SplitScreenController,
    private val shouldShowWindowingPill: Boolean,
    private val shouldShowBrowserPill: Boolean,
    private val captionHeight: Int
) {
    private val context: Context = parentDecor.mDecorWindowContext
    private val taskInfo: ActivityManager.RunningTaskInfo = parentDecor.mTaskInfo

    private val isViewAboveStatusBar: Boolean
        get() = (Flags.enableAdditionalWindowsAboveStatusBar() && !taskInfo.isFreeform)

    private var marginMenuTop = 0
    private var marginMenuStart = 0
    private var menuHeight = 0
    private var menuWidth = 0
    private var handleMenuAnimator: HandleMenuAnimator? = null

    @VisibleForTesting
    var handleMenuViewContainer: AdditionalViewContainer? = null

    // Position of the handle menu used for laying out the handle view.
    @VisibleForTesting
    val handleMenuPosition: PointF = PointF()

    // With the introduction of {@link AdditionalSystemViewContainer}, {@link mHandleMenuPosition}
    // may be in a different coordinate space than the input coordinates. Therefore, we still care
    // about the menu's coordinates relative to the display as a whole, so we need to maintain
    // those as well.
    private val globalMenuPosition: Point = Point()

    /**
     * An a array of windowing icon color based on current UI theme. First element of the
     * array is for inactive icons and the second is for active icons.
     */
    private val windowingIconColor: Array<ColorStateList>
        get() {
            val mode = (context.resources.configuration.uiMode
                    and Configuration.UI_MODE_NIGHT_MASK)
            val isNightMode = (mode == Configuration.UI_MODE_NIGHT_YES)
            val typedArray = context.obtainStyledAttributes(
                intArrayOf(
                    com.android.internal.R.attr.materialColorOnSurface,
                    com.android.internal.R.attr.materialColorPrimary
                )
            )
            val inActiveColor =
                typedArray.getColor(0, if (isNightMode) Color.WHITE else Color.BLACK)
            val activeColor = typedArray.getColor(1, if (isNightMode) Color.WHITE else Color.BLACK)
            typedArray.recycle()
            return arrayOf(
                ColorStateList.valueOf(inActiveColor),
                ColorStateList.valueOf(activeColor)
            )
        }

    init {
        loadHandleMenuDimensions()
        updateHandleMenuPillPositions()
    }

    fun show() {
        val ssg = SurfaceSyncGroup(TAG)
        val t = SurfaceControl.Transaction()

        createHandleMenuViewContainer(t, ssg)
        ssg.addTransaction(t)
        ssg.markSyncReady()
        setupHandleMenu()
        animateHandleMenu()
    }

    private fun createHandleMenuViewContainer(
        t: SurfaceControl.Transaction,
        ssg: SurfaceSyncGroup
    ) {
        val x = handleMenuPosition.x.toInt()
        val y = handleMenuPosition.y.toInt()
        handleMenuViewContainer =
            if (!taskInfo.isFreeform && Flags.enableAdditionalWindowsAboveStatusBar()) {
                AdditionalSystemViewContainer(
                    context = context,
                    layoutId = R.layout.desktop_mode_window_decor_handle_menu,
                    taskId = taskInfo.taskId,
                    x = x,
                    y = y,
                    width = menuWidth,
                    height = menuHeight
                )
            } else {
                parentDecor.addWindow(
                    R.layout.desktop_mode_window_decor_handle_menu, "Handle Menu",
                    t, ssg, x, y, menuWidth, menuHeight
                )
            }
        handleMenuViewContainer?.view?.let { view ->
            handleMenuAnimator =
                HandleMenuAnimator(view, menuWidth, captionHeight.toFloat())
        }
    }

    /**
     * Animates the appearance of the handle menu and its three pills.
     */
    private fun animateHandleMenu() {
        when (taskInfo.windowingMode) {
            WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
            WINDOWING_MODE_MULTI_WINDOW -> {
                handleMenuAnimator?.animateCaptionHandleExpandToOpen()
            }
            else -> {
                handleMenuAnimator?.animateOpen()
            }
        }
    }

    /**
     * Set up all three pills of the handle menu: app info pill, windowing pill, & more actions
     * pill.
     */
    private fun setupHandleMenu() {
        val handleMenu = handleMenuViewContainer?.view ?: return
        handleMenu.setOnTouchListener(onTouchListener)
        setupAppInfoPill(handleMenu)
        if (shouldShowWindowingPill) {
            setupWindowingPill(handleMenu)
        }
        setupMoreActionsPill(handleMenu)
        setupOpenInBrowserPill(handleMenu)
    }

    /**
     * Set up interactive elements of handle menu's app info pill.
     */
    private fun setupAppInfoPill(handleMenu: View) {
        val collapseBtn = handleMenu.findViewById<HandleMenuImageButton>(R.id.collapse_menu_button)
        val appIcon = handleMenu.findViewById<ImageView>(R.id.application_icon)
        val appName = handleMenu.findViewById<TextView>(R.id.application_name)
        collapseBtn.setOnClickListener(onClickListener)
        collapseBtn.taskInfo = taskInfo
        appIcon.setImageBitmap(appIconBitmap)
        appName.text = this.appName
    }

    /**
     * Set up interactive elements and color of handle menu's windowing pill.
     */
    private fun setupWindowingPill(handleMenu: View) {
        val fullscreenBtn = handleMenu.findViewById<ImageButton>(R.id.fullscreen_button)
        val splitscreenBtn = handleMenu.findViewById<ImageButton>(R.id.split_screen_button)
        val floatingBtn = handleMenu.findViewById<ImageButton>(R.id.floating_button)
        // TODO: Remove once implemented.
        floatingBtn.visibility = View.GONE

        val desktopBtn = handleMenu.findViewById<ImageButton>(R.id.desktop_button)
        fullscreenBtn.setOnClickListener(onClickListener)
        splitscreenBtn.setOnClickListener(onClickListener)
        floatingBtn.setOnClickListener(onClickListener)
        desktopBtn.setOnClickListener(onClickListener)
        // The button corresponding to the windowing mode that the task is currently in uses a
        // different color than the others.
        val iconColors = windowingIconColor
        val inActiveColorStateList = iconColors[0]
        val activeColorStateList = iconColors[1]
        fullscreenBtn.imageTintList = if (taskInfo.isFullscreen) {
            activeColorStateList
        } else {
            inActiveColorStateList
        }
        splitscreenBtn.imageTintList = if (taskInfo.windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            activeColorStateList
        } else {
            inActiveColorStateList
        }
        floatingBtn.imageTintList = if (taskInfo.windowingMode == WINDOWING_MODE_PINNED) {
            activeColorStateList
        } else {
            inActiveColorStateList
        }
        desktopBtn.imageTintList = if (taskInfo.isFreeform) {
            activeColorStateList
        } else {
            inActiveColorStateList
        }
    }

    /**
     * Set up interactive elements & height of handle menu's more actions pill
     */
    private fun setupMoreActionsPill(handleMenu: View) {
        if (!SHOULD_SHOW_MORE_ACTIONS_PILL) {
            handleMenu.findViewById<View>(R.id.more_actions_pill).visibility = View.GONE
        }
    }

    private fun setupOpenInBrowserPill(handleMenu: View) {
        if (!shouldShowBrowserPill) {
            handleMenu.findViewById<View>(R.id.open_in_browser_pill).visibility = View.GONE
            return
        }
        val browserButton = handleMenu.findViewById<Button>(R.id.open_in_browser_button)
        browserButton.setOnClickListener(onClickListener)
    }

    /**
     * Updates handle menu's position variables to reflect its next position.
     */
    private fun updateHandleMenuPillPositions() {
        val menuX: Int
        val menuY: Int
        val taskBounds = taskInfo.getConfiguration().windowConfiguration.bounds
        updateGlobalMenuPosition(taskBounds)
        if (layoutResId == R.layout.desktop_mode_app_header) {
            // Align the handle menu to the left side of the caption.
            menuX = marginMenuStart
            menuY = marginMenuTop
        } else {
            if (Flags.enableAdditionalWindowsAboveStatusBar()) {
                // In a focused decor, we use global coordinates for handle menu. Therefore we
                // need to account for other factors like split stage and menu/handle width to
                // center the menu.
                menuX = globalMenuPosition.x
                menuY = globalMenuPosition.y
            } else {
                menuX = (taskBounds.width() / 2) - (menuWidth / 2)
                menuY = marginMenuTop
            }
        }
        // Handle Menu position setup.
        handleMenuPosition.set(menuX.toFloat(), menuY.toFloat())
    }

    private fun updateGlobalMenuPosition(taskBounds: Rect) {
        when (taskInfo.windowingMode) {
            WINDOWING_MODE_FREEFORM -> {
                globalMenuPosition.set(
                    /* x = */ taskBounds.left + marginMenuStart,
                    /* y = */ taskBounds.top + marginMenuTop
                )
            }
            WINDOWING_MODE_FULLSCREEN -> {
                globalMenuPosition.set(
                    /* x = */ taskBounds.width() / 2 - (menuWidth / 2),
                    /* y = */ marginMenuTop
                )
            }
            WINDOWING_MODE_MULTI_WINDOW -> {
                val splitPosition = splitScreenController.getSplitPosition(taskInfo.taskId)
                val leftOrTopStageBounds = Rect()
                val rightOrBottomStageBounds = Rect()
                splitScreenController.getStageBounds(leftOrTopStageBounds, rightOrBottomStageBounds)
                // TODO(b/343561161): This needs to be calculated differently if the task is in
                //  top/bottom split.
                when (splitPosition) {
                    SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT -> {
                        globalMenuPosition.set(
                            /* x = */ leftOrTopStageBounds.width()
                                    + (rightOrBottomStageBounds.width() / 2)
                                    - (menuWidth / 2),
                            /* y = */ marginMenuTop
                        )
                    }
                    SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT -> {
                        globalMenuPosition.set(
                            /* x = */ (leftOrTopStageBounds.width() / 2)
                                    - (menuWidth / 2),
                            /* y = */ marginMenuTop
                        )
                    }
                }
            }
        }
    }

    /**
     * Update pill layout, in case task changes have caused positioning to change.
     */
    fun relayout(t: SurfaceControl.Transaction) {
        handleMenuViewContainer?.let { container ->
            updateHandleMenuPillPositions()
            container.setPosition(t, handleMenuPosition.x, handleMenuPosition.y)
        }
    }

    /**
     * Check a passed MotionEvent if a click or hover has occurred on any button on this caption
     * Note this should only be called when a regular onClick/onHover is not possible
     * (i.e. the button was clicked through status bar layer)
     *
     * @param ev the MotionEvent to compare against.
     */
    fun checkMotionEvent(ev: MotionEvent) {
        // If the menu view is above status bar, we can let the views handle input directly.
        if (isViewAboveStatusBar) return
        val handleMenu = handleMenuViewContainer?.view ?: return
        val collapse = handleMenu.findViewById<HandleMenuImageButton>(R.id.collapse_menu_button)
        val inputPoint = translateInputToLocalSpace(ev)
        val inputInCollapseButton = pointInView(collapse, inputPoint.x, inputPoint.y)
        val action = ev.actionMasked
        collapse.isHovered = inputInCollapseButton && action != MotionEvent.ACTION_UP
        collapse.isPressed = inputInCollapseButton && action == MotionEvent.ACTION_DOWN
        if (action == MotionEvent.ACTION_UP && inputInCollapseButton) {
            collapse.performClick()
        }
    }

    // Translate the input point from display coordinates to the same space as the handle menu.
    private fun translateInputToLocalSpace(ev: MotionEvent): PointF {
        return PointF(
            ev.x - handleMenuPosition.x,
            ev.y - handleMenuPosition.y
        )
    }

    /**
     * A valid menu input is one of the following:
     * An input that happens in the menu views.
     * Any input before the views have been laid out.
     *
     * @param inputPoint the input to compare against.
     */
    fun isValidMenuInput(inputPoint: PointF): Boolean {
        if (!viewsLaidOut()) return true
        if (!isViewAboveStatusBar) {
            return pointInView(
                handleMenuViewContainer?.view,
                inputPoint.x - handleMenuPosition.x,
                inputPoint.y - handleMenuPosition.y
            )
        } else {
            // Handle menu exists in a different coordinate space when added to WindowManager.
            // Therefore we must compare the provided input coordinates to global menu coordinates.
            // This includes factoring for split stage as input coordinates are relative to split
            // stage position, not relative to the display as a whole.
            val inputRelativeToMenu = PointF(
                inputPoint.x - globalMenuPosition.x,
                inputPoint.y - globalMenuPosition.y
            )
            if (splitScreenController.getSplitPosition(taskInfo.taskId)
                == SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT) {
                // TODO(b/343561161): This also needs to be calculated differently if
                //  the task is in top/bottom split.
                val leftStageBounds = Rect()
                splitScreenController.getStageBounds(leftStageBounds, Rect())
                inputRelativeToMenu.x += leftStageBounds.width().toFloat()
            }
            return pointInView(
                handleMenuViewContainer?.view,
                inputRelativeToMenu.x,
                inputRelativeToMenu.y
            )
        }
    }

    private fun pointInView(v: View?, x: Float, y: Float): Boolean {
        return v != null && v.left <= x && v.right >= x && v.top <= y && v.bottom >= y
    }

    /**
     * Check if the views for handle menu can be seen.
     */
    private fun viewsLaidOut(): Boolean = handleMenuViewContainer?.view?.isLaidOut ?: false

    private fun loadHandleMenuDimensions() {
        val resources = context.resources
        menuWidth = loadDimensionPixelSize(resources, R.dimen.desktop_mode_handle_menu_width)
        menuHeight = getHandleMenuHeight(resources)
        marginMenuTop = loadDimensionPixelSize(
            resources,
            R.dimen.desktop_mode_handle_menu_margin_top
        )
        marginMenuStart = loadDimensionPixelSize(
            resources,
            R.dimen.desktop_mode_handle_menu_margin_start
        )
    }

    /**
     * Determines handle menu height based on if windowing pill should be shown.
     */
    private fun getHandleMenuHeight(resources: Resources): Int {
        var menuHeight = loadDimensionPixelSize(resources, R.dimen.desktop_mode_handle_menu_height)
        if (!shouldShowWindowingPill) {
            menuHeight -= loadDimensionPixelSize(
                resources,
                R.dimen.desktop_mode_handle_menu_windowing_pill_height
            )
        }
        if (!SHOULD_SHOW_MORE_ACTIONS_PILL) {
            menuHeight -= loadDimensionPixelSize(
                resources,
                R.dimen.desktop_mode_handle_menu_more_actions_pill_height
            )
        }
        if (!shouldShowBrowserPill) {
            menuHeight -= loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_open_in_browser_pill_height)
        }
        return menuHeight
    }

    private fun loadDimensionPixelSize(resources: Resources, resourceId: Int): Int {
        if (resourceId == Resources.ID_NULL) {
            return 0
        }
        return resources.getDimensionPixelSize(resourceId)
    }

    fun close() {
        val after = {
            handleMenuViewContainer?.releaseView()
            handleMenuViewContainer = null
        }
        if (taskInfo.windowingMode == WINDOWING_MODE_FULLSCREEN ||
            taskInfo.windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            handleMenuAnimator?.animateCollapseIntoHandleClose(after)
        } else {
            handleMenuAnimator?.animateClose(after)
        }
    }

    companion object {
        private const val TAG = "HandleMenu"
        private const val SHOULD_SHOW_MORE_ACTIONS_PILL = false
    }
}
