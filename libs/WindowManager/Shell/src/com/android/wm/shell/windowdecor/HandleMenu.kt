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
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_OUTSIDE
import android.view.SurfaceControl
import android.view.View
import android.view.WindowInsets.Type.systemBars
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.DesktopModeFlags
import android.window.SurfaceSyncGroup
import androidx.annotation.StringRes
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
import com.android.wm.shell.windowdecor.common.calculateMenuPosition
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
    private val shouldShowChangeAspectRatioButton: Boolean,
    private val shouldShowDesktopModeButton: Boolean,
    private val isBrowserApp: Boolean,
    private val openInAppOrBrowserIntent: Intent?,
    private val captionWidth: Int,
    private val captionHeight: Int,
    captionX: Int,
    captionY: Int
) {
    private val context: Context = parentDecor.mDecorWindowContext
    private val taskInfo: RunningTaskInfo = parentDecor.mTaskInfo

    private val isViewAboveStatusBar: Boolean
        get() = (DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue() && !taskInfo.isFreeform)

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
        get() = openInAppOrBrowserIntent != null

    private val shouldShowMoreActionsPill: Boolean
        get() = SHOULD_SHOW_SCREENSHOT_BUTTON || shouldShowNewWindowButton ||
            shouldShowManageWindowsButton || shouldShowChangeAspectRatioButton

    init {
        updateHandleMenuPillPositions(captionX, captionY)
    }

    fun show(
        onToDesktopClickListener: () -> Unit,
        onToFullscreenClickListener: () -> Unit,
        onToSplitScreenClickListener: () -> Unit,
        onNewWindowClickListener: () -> Unit,
        onManageWindowsClickListener: () -> Unit,
        onChangeAspectRatioClickListener: () -> Unit,
        openInAppOrBrowserClickListener: (Intent) -> Unit,
        onOpenByDefaultClickListener: () -> Unit,
        onCloseMenuClickListener: () -> Unit,
        onOutsideTouchListener: () -> Unit,
        forceShowSystemBars: Boolean = false,
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
            onChangeAspectRatioClickListener = onChangeAspectRatioClickListener,
            openInAppOrBrowserClickListener = openInAppOrBrowserClickListener,
            onOpenByDefaultClickListener = onOpenByDefaultClickListener,
            onCloseMenuClickListener = onCloseMenuClickListener,
            onOutsideTouchListener = onOutsideTouchListener,
            forceShowSystemBars = forceShowSystemBars,
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
        onChangeAspectRatioClickListener: () -> Unit,
        openInAppOrBrowserClickListener: (Intent) -> Unit,
        onOpenByDefaultClickListener: () -> Unit,
        onCloseMenuClickListener: () -> Unit,
        onOutsideTouchListener: () -> Unit,
        forceShowSystemBars: Boolean = false,
    ) {
        val handleMenuView = HandleMenuView(
            context = context,
            menuWidth = menuWidth,
            captionHeight = captionHeight,
            shouldShowWindowingPill = shouldShowWindowingPill,
            shouldShowBrowserPill = shouldShowBrowserPill,
            shouldShowNewWindowButton = shouldShowNewWindowButton,
            shouldShowManageWindowsButton = shouldShowManageWindowsButton,
            shouldShowChangeAspectRatioButton = shouldShowChangeAspectRatioButton,
            shouldShowDesktopModeButton = shouldShowDesktopModeButton,
            isBrowserApp = isBrowserApp
        ).apply {
            bind(taskInfo, appIconBitmap, appName, shouldShowMoreActionsPill)
            this.onToDesktopClickListener = onToDesktopClickListener
            this.onToFullscreenClickListener = onToFullscreenClickListener
            this.onToSplitScreenClickListener = onToSplitScreenClickListener
            this.onNewWindowClickListener = onNewWindowClickListener
            this.onManageWindowsClickListener = onManageWindowsClickListener
            this.onChangeAspectRatioClickListener = onChangeAspectRatioClickListener
            this.onOpenInAppOrBrowserClickListener = {
                openInAppOrBrowserClickListener.invoke(openInAppOrBrowserIntent!!)
            }
            this.onOpenByDefaultClickListener = onOpenByDefaultClickListener
            this.onCloseMenuClickListener = onCloseMenuClickListener
            this.onOutsideTouchListener = onOutsideTouchListener
        }

        val x = handleMenuPosition.x.toInt()
        val y = handleMenuPosition.y.toInt()
        handleMenuViewContainer =
            if ((!taskInfo.isFreeform && DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue())
                    || forceShowSystemBars) {
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
                    view = handleMenuView.rootView,
                    forciblyShownTypes = if (forceShowSystemBars) { systemBars() } else { 0 },
                    ignoreCutouts = Flags.showAppHandleLargeScreens()
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
    private fun updateHandleMenuPillPositions(captionX: Int, captionY: Int) {
        val menuX: Int
        val menuY: Int
        val taskBounds = taskInfo.getConfiguration().windowConfiguration.bounds
        globalMenuPosition.set(
            calculateMenuPosition(
                splitScreenController,
                taskInfo,
                marginStart = marginMenuStart,
                marginMenuTop,
                captionX,
                captionY,
                captionWidth,
                menuWidth,
                context.isRtl()
            )
        )
        if (layoutResId == R.layout.desktop_mode_app_header) {
            // Align the handle menu to the start of the header.
            menuX = if (context.isRtl()) {
                taskBounds.width() - menuWidth - marginMenuStart
            } else {
                marginMenuStart
            }
            menuY = captionY + marginMenuTop
        } else {
            if (DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
                // In a focused decor, we use global coordinates for handle menu. Therefore we
                // need to account for other factors like split stage and menu/handle width to
                // center the menu.
                menuX = globalMenuPosition.x
                menuY = globalMenuPosition.y
            } else {
                menuX = (taskBounds.width() / 2) - (menuWidth / 2)
                menuY = captionY + marginMenuTop
            }
        }
        // Handle Menu position setup.
        handleMenuPosition.set(menuX.toFloat(), menuY.toFloat())
    }

    /**
     * Update pill layout, in case task changes have caused positioning to change.
     */
    fun relayout(
        t: SurfaceControl.Transaction,
        captionX: Int,
        captionY: Int,
    ) {
        handleMenuViewContainer?.let { container ->
            updateHandleMenuPillPositions(captionX, captionY)
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
        if (!shouldShowChangeAspectRatioButton) {
            menuHeight -= loadDimensionPixelSize(
                R.dimen.desktop_mode_handle_menu_change_aspect_ratio_height)
        }
        if (!shouldShowMoreActionsPill) {
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

    private fun Context.isRtl() =
        resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    fun close() {
        handleMenuView?.animateCloseMenu {
            handleMenuViewContainer?.releaseView()
            handleMenuViewContainer = null
        }
    }

    /** The view within the Handle Menu, with options to change the windowing mode and more. */
    @SuppressLint("ClickableViewAccessibility")
    class HandleMenuView(
        private val context: Context,
        menuWidth: Int,
        captionHeight: Int,
        private val shouldShowWindowingPill: Boolean,
        private val shouldShowBrowserPill: Boolean,
        private val shouldShowNewWindowButton: Boolean,
        private val shouldShowManageWindowsButton: Boolean,
        private val shouldShowChangeAspectRatioButton: Boolean,
        private val shouldShowDesktopModeButton: Boolean,
        private val isBrowserApp: Boolean
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
        private val changeAspectRatioBtn = moreActionsPill
            .requireViewById<Button>(R.id.change_aspect_ratio_button)

        // Open in Browser/App Pill.
        private val openInAppOrBrowserPill = rootView.requireViewById<View>(
            R.id.open_in_app_or_browser_pill)
        private val openInAppOrBrowserBtn = openInAppOrBrowserPill.requireViewById<Button>(
            R.id.open_in_app_or_browser_button)
        private val openByDefaultBtn = openInAppOrBrowserPill.requireViewById<ImageButton>(
            R.id.open_by_default_button)
        private val decorThemeUtil = DecorThemeUtil(context)
        private val animator = HandleMenuAnimator(rootView, menuWidth, captionHeight.toFloat())

        private lateinit var taskInfo: RunningTaskInfo
        private lateinit var style: MenuStyle

        var onToDesktopClickListener: (() -> Unit)? = null
        var onToFullscreenClickListener: (() -> Unit)? = null
        var onToSplitScreenClickListener: (() -> Unit)? = null
        var onNewWindowClickListener: (() -> Unit)? = null
        var onManageWindowsClickListener: (() -> Unit)? = null
        var onChangeAspectRatioClickListener: (() -> Unit)? = null
        var onOpenInAppOrBrowserClickListener: (() -> Unit)? = null
        var onOpenByDefaultClickListener: (() -> Unit)? = null
        var onCloseMenuClickListener: (() -> Unit)? = null
        var onOutsideTouchListener: (() -> Unit)? = null

        init {
            fullscreenBtn.setOnClickListener { onToFullscreenClickListener?.invoke() }
            splitscreenBtn.setOnClickListener { onToSplitScreenClickListener?.invoke() }
            desktopBtn.setOnClickListener { onToDesktopClickListener?.invoke() }
            openInAppOrBrowserBtn.setOnClickListener { onOpenInAppOrBrowserClickListener?.invoke() }
            openByDefaultBtn.setOnClickListener {
                onOpenByDefaultClickListener?.invoke()
            }
            collapseMenuButton.setOnClickListener { onCloseMenuClickListener?.invoke() }
            newWindowBtn.setOnClickListener { onNewWindowClickListener?.invoke() }
            manageWindowBtn.setOnClickListener { onManageWindowsClickListener?.invoke() }
            changeAspectRatioBtn.setOnClickListener { onChangeAspectRatioClickListener?.invoke() }

            rootView.setOnTouchListener { _, event ->
                if (event.actionMasked == ACTION_OUTSIDE) {
                    onOutsideTouchListener?.invoke()
                    return@setOnTouchListener false
                }
                return@setOnTouchListener true
            }
        }

        /** Binds the menu views to the new data. */
        fun bind(
            taskInfo: RunningTaskInfo,
            appIconBitmap: Bitmap?,
            appName: CharSequence?,
            shouldShowMoreActionsPill: Boolean
        ) {
            this.taskInfo = taskInfo
            this.style = calculateMenuStyle(taskInfo)

            bindAppInfoPill(style, appIconBitmap, appName)
            if (shouldShowWindowingPill) {
                bindWindowingPill(style)
            }
            moreActionsPill.isGone = !shouldShowMoreActionsPill
            if (shouldShowMoreActionsPill) {
                bindMoreActionsPill(style)
            }
            bindOpenInAppOrBrowserPill(style)
        }

        /** Animates the menu openInAppOrBrowserg. */
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
            desktopBtn.isGone = !shouldShowDesktopModeButton
            desktopBtn.isSelected = taskInfo.isFreeform
            desktopBtn.isEnabled = !taskInfo.isFreeform
            desktopBtn.imageTintList = style.windowingButtonColor
        }

        private fun bindMoreActionsPill(style: MenuStyle) {
            moreActionsPill.background.setTint(style.backgroundColor)

            arrayOf(
                screenshotBtn to SHOULD_SHOW_SCREENSHOT_BUTTON,
                newWindowBtn to shouldShowNewWindowButton,
                manageWindowBtn to shouldShowManageWindowsButton,
                changeAspectRatioBtn to shouldShowChangeAspectRatioButton,
            ).forEach {
                val button = it.first
                val shouldShow = it.second
                button.apply {
                    isGone = !shouldShow
                    setTextColor(style.textColor)
                    compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
                }
            }
        }

        private fun bindOpenInAppOrBrowserPill(style: MenuStyle) {
            openInAppOrBrowserPill.apply {
                isGone = !shouldShowBrowserPill
                background.setTint(style.backgroundColor)
            }

            val btnText = if (isBrowserApp) {
                getString(R.string.open_in_app_text)
            } else {
                getString(R.string.open_in_browser_text)
            }
            openInAppOrBrowserBtn.apply {
                text = btnText
                contentDescription = btnText
                setTextColor(style.textColor)
                compoundDrawableTintList = ColorStateList.valueOf(style.textColor)
            }

            openByDefaultBtn.imageTintList = ColorStateList.valueOf(style.textColor)
        }

        private fun getString(@StringRes resId: Int): String = context.resources.getString(resId)

        private data class MenuStyle(
            @ColorInt val backgroundColor: Int,
            @ColorInt val textColor: Int,
            val windowingButtonColor: ColorStateList,
        )
    }

    companion object {
        private const val TAG = "HandleMenu"
        private const val SHOULD_SHOW_SCREENSHOT_BUTTON = false

        /**
         * Returns whether the aspect ratio button should be shown for the task. It usually means
         * that the task is on a large screen with ignore-orientation-request.
         */
        fun shouldShowChangeAspectRatioButton(taskInfo: RunningTaskInfo): Boolean =
            taskInfo.appCompatTaskInfo.eligibleForUserAspectRatioButton() &&
                taskInfo.windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
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
        shouldShowChangeAspectRatioButton: Boolean,
        shouldShowDesktopModeButton: Boolean,
        isBrowserApp: Boolean,
        openInAppOrBrowserIntent: Intent?,
        captionWidth: Int,
        captionHeight: Int,
        captionX: Int,
        captionY: Int,
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
        shouldShowChangeAspectRatioButton: Boolean,
        shouldShowDesktopModeButton: Boolean,
        isBrowserApp: Boolean,
        openInAppOrBrowserIntent: Intent?,
        captionWidth: Int,
        captionHeight: Int,
        captionX: Int,
        captionY: Int,
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
            shouldShowChangeAspectRatioButton,
            shouldShowDesktopModeButton,
            isBrowserApp,
            openInAppOrBrowserIntent,
            captionWidth,
            captionHeight,
            captionX,
            captionY,
        )
    }
}
