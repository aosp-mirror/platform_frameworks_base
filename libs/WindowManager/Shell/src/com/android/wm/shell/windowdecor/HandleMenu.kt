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

import android.annotation.ColorInt
import android.annotation.DimenRes
import android.annotation.SuppressLint
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_OUTSIDE
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.SurfaceSyncGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.isGone
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.shared.split.SplitScreenConstants
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewContainer
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.extension.isFullscreen
import com.android.wm.shell.windowdecor.extension.isMultiWindow
import com.android.wm.shell.windowdecor.extension.isPinned

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
    private val windowManagerWrapper: WindowManagerWrapper,
    private val layoutResId: Int,
    private val appIconBitmap: Bitmap?,
    private val appName: CharSequence?,
    private val splitScreenController: SplitScreenController,
    private val shouldShowWindowingPill: Boolean,
    private val shouldShowNewWindowButton: Boolean,
    private val shouldShowManageWindowsButton: Boolean,
    private val openInBrowserLink: Uri?,
    private val captionWidth: Int,
    private val captionHeight: Int,
    captionX: Int
) {
    private val context: Context = parentDecor.mDecorWindowContext
    private val taskInfo: RunningTaskInfo = parentDecor.mTaskInfo

    private val isViewAboveStatusBar: Boolean
        get() = (Flags.enableHandleInputFix() && !taskInfo.isFreeform)

    private val pillElevation: Int = loadDimensionPixelSize(
        R.dimen.desktop_mode_handle_menu_pill_elevation)
    private val pillTopMargin: Int = loadDimensionPixelSize(
        R.dimen.desktop_mode_handle_menu_pill_spacing_margin)
    private val menuWidth = loadDimensionPixelSize(
        R.dimen.desktop_mode_handle_menu_width) + pillElevation
    private val menuHeight = getHandleMenuHeight()
    private val marginMenuTop = loadDimensionPixelSize(R.dimen.desktop_mode_handle_menu_margin_top)
    private val marginMenuStart = loadDimensionPixelSize(
        R.dimen.desktop_mode_handle_menu_margin_start)

    @VisibleForTesting
    var handleMenuViewContainer: AdditionalViewContainer? = null
    private var handleMenuView: HandleMenuView? = null

    // Position of the handle menu used for laying out the handle view.
    @VisibleForTesting
    val handleMenuPosition: PointF = PointF()

    // With the introduction of {@link AdditionalSystemViewContainer}, {@link mHandleMenuPosition}
    // may be in a different coordinate space than the input coordinates. Therefore, we still care
    // about the menu's coordinates relative to the display as a whole, so we need to maintain
    // those as well.
    private val globalMenuPosition: Point = Point()

    private val shouldShowBrowserPill: Boolean
        get() = openInBrowserLink != null

    init {
        updateHandleMenuPillPositions(captionX)
    }

    fun show(
        onToDesktopClickListener: () -> Unit,
        onToFullscreenClickListener: () -> Unit,
        onToSplitScreenClickListener: () -> Unit,
        onNewWindowClickListener: () -> Unit,
        onManageWindowsClickListener: () -> Unit,
        openInBrowserClickListener: (Uri) -> Unit,
        onCloseMenuClickListener: () -> Unit,
        onOutsideTouchListener: () -> Unit,
    ) {
        val ssg = SurfaceSyncGroup(TAG)
        val t = SurfaceControl.Transaction()

        createHandleMenu(
            t = t,
            ssg = ssg,
            onToDesktopClickListener = onToDesktopClickListener,
            onToFullscreenClickListener = onToFullscreenClickListener,
            onToSplitScreenClickListener = onToSplitScreenClickListener,
            onNewWindowClickListener = onNewWindowClickListener,
            onManageWindowsClickListener = onManageWindowsClickListener,
            openInBrowserClickListener = openInBrowserClickListener,
            onCloseMenuClickListener = onCloseMenuClickListener,
            onOutsideTouchListener = onOutsideTouchListener,
        )
        ssg.addTransaction(t)
        ssg.markSyncReady()

        handleMenuView?.animateOpenMenu()
    }

    private fun createHandleMenu(
        t: SurfaceControl.Transaction,
        ssg: SurfaceSyncGroup,
        onToDesktopClickListener: () -> Unit,
        onToFullscreenClickListener: () -> Unit,
        onToSplitScreenClickListener: () -> Unit,
        onNewWindowClickListener: () -> Unit,
        onManageWindowsClickListener: () -> Unit,
        openInBrowserClickListener: (Uri) -> Unit,
        onCloseMenuClickListener: () -> Unit,
        onOutsideTouchListener: () -> Unit
    ) {
        val handleMenuView = HandleMenuView(
            context = context,
            menuWidth = menuWidth,
            captionHeight = captionHeight,
            shouldShowWindowingPill = shouldShowWindowingPill,
            shouldShowBrowserPill = shouldShowBrowserPill,
            shouldShowNewWindowButton = shouldShowNewWindowButton,
            shouldShowManageWindowsButton = shouldShowManageWindowsButton
        ).apply {
            bind(taskInfo, appIconBitmap, appName)
            this.onToDesktopClickListener = onToDesktopClickListener
            this.onToFullscreenClickListener = onToFullscreenClickListener
            this.onToSplitScreenClickListener = onToSplitScreenClickListener
            this.onNewWindowClickListener = onNewWindowClickListener
            this.onManageWindowsClickListener = onManageWindowsClickListener
            this.onOpenInBrowserClickListener = {
                openInBrowserClickListener.invoke(openInBrowserLink!!)
            }
            this.onCloseMenuClickListener = onCloseMenuClickListener
            this.onOutsideTouchListener = onOutsideTouchListener
        }

        val x = handleMenuPosition.x.toInt()
        val y = handleMenuPosition.y.toInt()
        handleMenuViewContainer =
            if (!taskInfo.isFreeform && Flags.enableHandleInputFix()) {
                AdditionalSystemViewContainer(
                    windowManagerWrapper = windowManagerWrapper,
                    taskId = taskInfo.taskId,
                    x = x,
                    y = y,
                    width = menuWidth,
                    height = menuHeight,
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    view = handleMenuView.rootView
                )
            } else {
                parentDecor.addWindow(
                    handleMenuView.rootView, "Handle Menu", t, ssg, x, y, menuWidth, menuHeight
                )
            }

        this.handleMenuView = handleMenuView
    }

    /**
     * Updates handle menu's position variables to reflect its next position.
     */
    private fun updateHandleMenuPillPositions(captionX: Int) {
        val menuX: Int
        val menuY: Int
        val taskBounds = taskInfo.getConfiguration().windowConfiguration.bounds
        updateGlobalMenuPosition(taskBounds, captionX)
        if (layoutResId == R.layout.desktop_mode_app_header) {
            // Align the handle menu to the left side of the caption.
            menuX = marginMenuStart
            menuY = marginMenuTop
        } else {
            if (Flags.enableHandleInputFix()) {
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

    private fun updateGlobalMenuPosition(taskBounds: Rect, captionX: Int) {
        val nonFreeformX = captionX + (captionWidth / 2) - (menuWidth / 2)
        when {
            taskInfo.isFreeform -> {
                globalMenuPosition.set(
                    /* x = */ taskBounds.left + marginMenuStart,
                    /* y = */ taskBounds.top + marginMenuTop
                )
            }
            taskInfo.isFullscreen -> {
                globalMenuPosition.set(
                    /* x = */ nonFreeformX,
                    /* y = */ marginMenuTop
                )
            }
            taskInfo.isMultiWindow -> {
                val splitPosition = splitScreenController.getSplitPosition(taskInfo.taskId)
                val leftOrTopStageBounds = Rect()
                val rightOrBottomStageBounds = Rect()
                splitScreenController.getStageBounds(leftOrTopStageBounds, rightOrBottomStageBounds)
                // TODO(b/343561161): This needs to be calculated differently if the task is in
                //  top/bottom split.
                when (splitPosition) {
                    SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT -> {
                        globalMenuPosition.set(
                            /* x = */ leftOrTopStageBounds.width() + nonFreeformX,
                            /* y = */ marginMenuTop
                        )
                    }
                    SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT -> {
                        globalMenuPosition.set(
                            /* x = */ nonFreeformX,
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
    fun relayout(
        t: SurfaceControl.Transaction,
        captionX: Int
    ) {
        handleMenuViewContainer?.let { container ->
            updateHandleMenuPillPositions(captionX)
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
        val inputPoint = translateInputToLocalSpace(ev)
        handleMenuView?.checkMotionEvent(ev, inputPoint)
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

    /**
     * Determines handle menu height based the max size and the visibility of pills.
     */
    private fun getHandleMenuHeight(): Int {
        var menuHeight = loadDimensionPixelSize(
            R.dimen.desktop_mode_handle_menu_height) + pillElevation
        if (!shouldShowWindowingPill) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_windowing_pill_height)
            menuHeight -= pillTopMargin
        }
        if (!SHOULD_SHOW_SCREENSHOT_BUTTON) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_screenshot_height
            )
        }
        if (!shouldShowNewWindowButton) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_new_window_height
            )
        }
        if (!shouldShowManageWindowsButton) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_manage_windows_height
            )
        }
        if (!SHOULD_SHOW_SCREENSHOT_BUTTON && !shouldShowNewWindowButton
            && !shouldShowManageWindowsButton) {
            menuHeight -= pillTopMargin
        }
        if (!shouldShowBrowserPill) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_open_in_browser_pill_height)
            menuHeight -= pillTopMargin
        }
        return menuHeight
    }

    private fun loadDimensionPixelSize(@DimenRes resourceId: Int): Int {
        if (resourceId == Resources.ID_NULL) {
            return 0
        }
        return context.resources.getDimensionPixelSize(resourceId)
    }

    fun close() {
        handleMenuView?.animateCloseMenu {
            handleMenuViewContainer?.releaseView()
            handleMenuViewContainer = null
        }
    }

    /** The view within the Handle Menu, with options to change the windowing mode and more. */
    @SuppressLint("ClickableViewAccessibility")
    class HandleMenuView(
        context: Context,
        menuWidth: Int,
        captionHeight: Int,
        private val shouldShowWindowingPill: Boolean,
        private val shouldShowBrowserPill: Boolean,
        private val shouldShowNewWindowButton: Boolean,
        private val shouldShowManageWindowsButton: Boolean
    ) {
        val rootView = LayoutInflater.from(context)
            .inflate(R.layout.desktop_mode_window_decor_handle_menu, null /* root */) as View

        // App Info Pill.
        private val appInfoPill = rootView.requireViewById<View>(R.id.app_info_pill)
        private val collapseMenuButton = appInfoPill.requireViewById<HandleMenuImageButton>(
            R.id.collapse_menu_button)
        private val appIconView = appInfoPill.requireViewById<ImageView>(R.id.application_icon)
        private val appNameView = appInfoPill.requireViewById<TextView>(R.id.application_name)

        // Windowing Pill.
        private val windowingPill = rootView.requireViewById<View>(R.id.windowing_pill)
        private val fullscreenBtn = windowingPill.requireViewById<ImageButton>(
            R.id.fullscreen_button)
        private val splitscreenBtn = windowingPill.requireViewById<ImageButton>(
            R.id.split_screen_button)
        private val floatingBtn = windowingPill.requireViewById<ImageButton>(R.id.floating_button)
        private val desktopBtn = windowingPill.requireViewById<ImageButton>(R.id.desktop_button)

        // More Actions Pill.
        private val moreActionsPill = rootView.requireViewById<View>(R.id.more_actions_pill)
        private val screenshotBtn = moreActionsPill.requireViewById<Button>(R.id.screenshot_button)
        private val newWindowBtn = moreActionsPill.requireViewById<Button>(R.id.new_window_button)
        private val manageWindowBtn = moreActionsPill
            .requireViewById<Button>(R.id.manage_windows_button)

        // Open in Browser Pill.
        private val openInBrowserPill = rootView.requireViewById<View>(R.id.open_in_browser_pill)
        private val browserBtn = openInBrowserPill.requireViewById<Button>(
            R.id.open_in_browser_button)

        private val decorThemeUtil = DecorThemeUtil(context)
        private val animator = HandleMenuAnimator(rootView, menuWidth, captionHeight.toFloat())

        private lateinit var taskInfo: RunningTaskInfo
        private lateinit var style: MenuStyle

        var onToDesktopClickListener: (() -> Unit)? = null
        var onToFullscreenClickListener: (() -> Unit)? = null
        var onToSplitScreenClickListener: (() -> Unit)? = null
        var onNewWindowClickListener: (() -> Unit)? = null
        var onManageWindowsClickListener: (() -> Unit)? = null
        var onOpenInBrowserClickListener: (() -> Unit)? = null
        var onCloseMenuClickListener: (() -> Unit)? = null
        var onOutsideTouchListener: (() -> Unit)? = null

        init {
            fullscreenBtn.setOnClickListener { onToFullscreenClickListener?.invoke() }
            splitscreenBtn.setOnClickListener { onToSplitScreenClickListener?.invoke() }
            desktopBtn.setOnClickListener { onToDesktopClickListener?.invoke() }
            browserBtn.setOnClickListener { onOpenInBrowserClickListener?.invoke() }
            collapseMenuButton.setOnClickListener { onCloseMenuClickListener?.invoke() }
            newWindowBtn.setOnClickListener { onNewWindowClickListener?.invoke() }
            manageWindowBtn.setOnClickListener { onManageWindowsClickListener?.invoke() }

            rootView.setOnTouchListener { _, event ->
                if (event.actionMasked == ACTION_OUTSIDE) {
                    onOutsideTouchListener?.invoke()
                    return@setOnTouchListener false
                }
                return@setOnTouchListener true
            }
        }

        /** Binds the menu views to the new data. */
        fun bind(taskInfo: RunningTaskInfo, appIconBitmap: Bitmap?, appName: CharSequence?) {
            this.taskInfo = taskInfo
            this.style = calculateMenuStyle(taskInfo)

            bindAppInfoPill(style, appIconBitmap, appName)
            if (shouldShowWindowingPill) {
                bindWindowingPill(style)
            }
            bindMoreActionsPill(style)
            bindOpenInBrowserPill(style)
        }

        /** Animates the menu opening. */
        fun animateOpenMenu() {
            if (taskInfo.isFullscreen || taskInfo.isMultiWindow) {
                animator.animateCaptionHandleExpandToOpen()
            } else {
                animator.animateOpen()
            }
        }

        /** Animates the menu closing. */
        fun animateCloseMenu(onAnimFinish: () -> Unit) {
            if (taskInfo.isFullscreen || taskInfo.isMultiWindow) {
                animator.animateCollapseIntoHandleClose(onAnimFinish)
            } else {
                animator.animateClose(onAnimFinish)
            }
        }

        /**
         * Checks whether a motion event falls inside this menu, and invokes a click of the
         * collapse button if needed.
         * Note: should only be called when regular click detection doesn't work because input is
         * detected through the status bar layer with a global input monitor.
         */
        fun checkMotionEvent(ev: MotionEvent, inputPointLocal: PointF) {
            val inputInCollapseButton = pointInView(
                collapseMenuButton,
                inputPointLocal.x,
                inputPointLocal.y
            )
            val action = ev.actionMasked
            collapseMenuButton.isHovered = inputInCollapseButton
                    && action != MotionEvent.ACTION_UP
            collapseMenuButton.isPressed = inputInCollapseButton
                    && action == MotionEvent.ACTION_DOWN
            if (action == MotionEvent.ACTION_UP && inputInCollapseButton) {
                collapseMenuButton.performClick()
            }
        }

        private fun pointInView(v: View?, x: Float, y: Float): Boolean {
            return v != null && v.left <= x && v.right >= x && v.top <= y && v.bottom >= y
        }

        private fun calculateMenuStyle(taskInfo: RunningTaskInfo): MenuStyle {
            val colorScheme = decorThemeUtil.getColorScheme(taskInfo)
            return MenuStyle(
                backgroundColor = colorScheme.surfaceBright.toArgb(),
                textColor = colorScheme.onSurface.toArgb(),
                windowingButtonColor = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(android.R.attr.state_selected),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        colorScheme.onSurface.toArgb(),
                        colorScheme.onSurface.toArgb(),
                        colorScheme.primary.toArgb(),
                        colorScheme.onSurface.toArgb(),
                    )
                ),
            )
        }

        private fun bindAppInfoPill(
            style: MenuStyle,
            appIconBitmap: Bitmap?,
            appName: CharSequence?
        ) {
            appInfoPill.background.setTint(style.backgroundColor)

            collapseMenuButton.apply {
                imageTintList = ColorStateList.valueOf(style.textColor)
                this.taskInfo = this@HandleMenuView.taskInfo
            }
            appIconView.setImageBitmap(appIconBitmap)
            appNameView.apply {
                text = appName
                setTextColor(style.textColor)
            }
        }

        private fun bindWindowingPill(style: MenuStyle) {
            windowingPill.background.setTint(style.backgroundColor)

            // TODO: Remove once implemented.
            floatingBtn.visibility = View.GONE

            fullscreenBtn.isSelected = taskInfo.isFullscreen
            fullscreenBtn.isEnabled = !taskInfo.isFullscreen
            fullscreenBtn.imageTintList = style.windowingButtonColor
            splitscreenBtn.isSelected = taskInfo.isMultiWindow
            splitscreenBtn.isEnabled = !taskInfo.isMultiWindow
            splitscreenBtn.imageTintList = style.windowingButtonColor
            floatingBtn.isSelected = taskInfo.isPinned
            floatingBtn.isEnabled = !taskInfo.isPinned
            floatingBtn.imageTintList = style.windowingButtonColor
            desktopBtn.isSelected = taskInfo.isFreeform
            desktopBtn.isEnabled = !taskInfo.isFreeform
            desktopBtn.imageTintList = style.windowingButtonColor
        }

        private fun bindMoreActionsPill(style: MenuStyle) {
            moreActionsPill.apply {
                isGone = !shouldShowNewWindowButton && !SHOULD_SHOW_SCREENSHOT_BUTTON
                        && !shouldShowManageWindowsButton
            }
            screenshotBtn.apply {
                isGone = !SHOULD_SHOW_SCREENSHOT_BUTTON
                background.setTint(style.backgroundColor)
                setTextColor(style.textColor)
                compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
            }
            newWindowBtn.apply {
                isGone = !shouldShowNewWindowButton
                background.setTint(style.backgroundColor)
                setTextColor(style.textColor)
                compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
            }
            manageWindowBtn.apply {
                isGone = !shouldShowManageWindowsButton
                background.setTint(style.backgroundColor)
                setTextColor(style.textColor)
                compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
            }
        }

        private fun bindOpenInBrowserPill(style: MenuStyle) {
            openInBrowserPill.apply {
                isGone = !shouldShowBrowserPill
                background.setTint(style.backgroundColor)
            }

            browserBtn.apply {
                setTextColor(style.textColor)
                compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
            }
        }

        private data class MenuStyle(
            @ColorInt val backgroundColor: Int,
            @ColorInt val textColor: Int,
            val windowingButtonColor: ColorStateList,
        )
    }

    companion object {
        private const val TAG = "HandleMenu"
        private const val SHOULD_SHOW_SCREENSHOT_BUTTON = false
    }
}

/** A factory interface to create a [HandleMenu]. */
interface HandleMenuFactory {
    fun create(
        parentDecor: DesktopModeWindowDecoration,
        windowManagerWrapper: WindowManagerWrapper,
        layoutResId: Int,
        appIconBitmap: Bitmap?,
        appName: CharSequence?,
        splitScreenController: SplitScreenController,
        shouldShowWindowingPill: Boolean,
        shouldShowNewWindowButton: Boolean,
        shouldShowManageWindowsButton: Boolean,
        openInBrowserLink: Uri?,
        captionWidth: Int,
        captionHeight: Int,
        captionX: Int
    ): HandleMenu
}

/** A [HandleMenuFactory] implementation that creates a [HandleMenu].  */
object DefaultHandleMenuFactory : HandleMenuFactory {
    override fun create(
        parentDecor: DesktopModeWindowDecoration,
        windowManagerWrapper: WindowManagerWrapper,
        layoutResId: Int,
        appIconBitmap: Bitmap?,
        appName: CharSequence?,
        splitScreenController: SplitScreenController,
        shouldShowWindowingPill: Boolean,
        shouldShowNewWindowButton: Boolean,
        shouldShowManageWindowsButton: Boolean,
        openInBrowserLink: Uri?,
        captionWidth: Int,
        captionHeight: Int,
        captionX: Int
    ): HandleMenu {
        return HandleMenu(
            parentDecor,
            windowManagerWrapper,
            layoutResId,
            appIconBitmap,
            appName,
            splitScreenController,
            shouldShowWindowingPill,
            shouldShowNewWindowButton,
            shouldShowManageWindowsButton,
            openInBrowserLink,
            captionWidth,
            captionHeight,
            captionX
        )
    }
}
