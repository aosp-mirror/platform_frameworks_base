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
import android.annotation.ColorInt
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.StateSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_HOVER_ENTER
import android.view.MotionEvent.ACTION_HOVER_EXIT
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.View.SCALE_Y
import android.view.View.TRANSLATION_Y
import android.view.View.TRANSLATION_Z
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.Button
import android.widget.TextView
import android.window.TaskConstants
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.animation.addListener
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.OPACITY_12
import com.android.wm.shell.windowdecor.common.OPACITY_40
import com.android.wm.shell.windowdecor.common.OnTaskActionClickListener
import com.android.wm.shell.windowdecor.common.withAlpha
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
        private val decorWindowContext: Context,
        private val menuPosition: PointF,
        private val transactionSupplier: Supplier<Transaction> = Supplier { Transaction() }
) {
    private var maximizeMenu: AdditionalViewHostViewContainer? = null
    private var maximizeMenuView: MaximizeMenuView? = null
    private lateinit var viewHost: SurfaceControlViewHost
    private lateinit var leash: SurfaceControl
    private val cornerRadius = loadDimensionPixelSize(
            R.dimen.desktop_mode_maximize_menu_corner_radius
    ).toFloat()
    private val menuWidth = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_width)
    private val menuHeight = loadDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_height)
    private val menuPadding = loadDimensionPixelSize(R.dimen.desktop_mode_menu_padding)

    /** Position the menu relative to the caption's position. */
    fun positionMenu(position: PointF, t: Transaction) {
        menuPosition.set(position)
        t.setPosition(leash, menuPosition.x, menuPosition.y)
    }

    /** Creates and shows the maximize window. */
    fun show(
        onMaximizeClickListener: OnTaskActionClickListener,
        onLeftSnapClickListener: OnTaskActionClickListener,
        onRightSnapClickListener: OnTaskActionClickListener,
        onHoverListener: (Boolean) -> Unit
    ) {
        if (maximizeMenu != null) return
        createMaximizeMenu(
            onMaximizeClickListener = onMaximizeClickListener,
            onLeftSnapClickListener = onLeftSnapClickListener,
            onRightSnapClickListener = onRightSnapClickListener,
            onHoverListener = onHoverListener
        )
        maximizeMenuView?.animateOpenMenu()
    }

    /** Closes the maximize window and releases its view. */
    fun close() {
        maximizeMenuView?.cancelAnimation()
        maximizeMenu?.releaseView()
        maximizeMenu = null
        maximizeMenuView = null
    }

    /** Create a maximize menu that is attached to the display area. */
    private fun createMaximizeMenu(
        onMaximizeClickListener: OnTaskActionClickListener,
        onLeftSnapClickListener: OnTaskActionClickListener,
        onRightSnapClickListener: OnTaskActionClickListener,
        onHoverListener: (Boolean) -> Unit
    ) {
        val t = transactionSupplier.get()
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
        maximizeMenuView = MaximizeMenuView(
            context = decorWindowContext,
            menuHeight = menuHeight,
            menuPadding = menuPadding,
        ).also { menuView ->
            val taskId = taskInfo.taskId
            menuView.bind(taskInfo)
            menuView.onMaximizeClickListener = {
                onMaximizeClickListener.onClick(taskId, "maximize_menu_option")
            }
            menuView.onLeftSnapClickListener = {
                onLeftSnapClickListener.onClick(taskId, "left_snap_option")
            }
            menuView.onRightSnapClickListener = {
                onRightSnapClickListener.onClick(taskId, "right_snap_option")
            }
            menuView.onMenuHoverListener = onHoverListener
            viewHost.setView(menuView.rootView, lp)
        }

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

    private fun loadDimensionPixelSize(resourceId: Int): Int {
        return if (resourceId == Resources.ID_NULL) {
            0
        } else {
            decorWindowContext.resources.getDimensionPixelSize(resourceId)
        }
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

    /**
     * The view within the Maximize Menu, presents maximize, restore and snap-to-side options for
     * resizing a Task.
     */
    class MaximizeMenuView(
        context: Context,
        private val menuHeight: Int,
        private val menuPadding: Int,
    ) {
        val rootView = LayoutInflater.from(context)
            .inflate(R.layout.desktop_mode_window_decor_maximize_menu, null /* root */) as ViewGroup
        private val container = requireViewById(R.id.container)
        private val overlay = requireViewById(R.id.maximize_menu_overlay)
        private val maximizeText =
            requireViewById(R.id.maximize_menu_maximize_window_text) as TextView
        private val maximizeButton =
            requireViewById(R.id.maximize_menu_maximize_button) as Button
        private val snapWindowText =
            requireViewById(R.id.maximize_menu_snap_window_text) as TextView
        private val snapRightButton =
            requireViewById(R.id.maximize_menu_snap_right_button) as Button
        private val snapLeftButton =
            requireViewById(R.id.maximize_menu_snap_left_button) as Button
        private val snapButtonsLayout =
            requireViewById(R.id.maximize_menu_snap_menu_layout)

        private val decorThemeUtil = DecorThemeUtil(context)

        private val outlineRadius = context.resources
            .getDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_buttons_outline_radius)
        private val outlineStroke = context.resources
            .getDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_buttons_outline_stroke)
        private val fillPadding = context.resources
            .getDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_buttons_fill_padding)
        private val fillRadius = context.resources
            .getDimensionPixelSize(R.dimen.desktop_mode_maximize_menu_buttons_fill_radius)

        private val hoverTempRect = Rect()
        private val openMenuAnimatorSet = AnimatorSet()
        private lateinit var taskInfo: RunningTaskInfo
        private lateinit var style: MenuStyle

        /** Invoked when the maximize or restore option is clicked. */
        var onMaximizeClickListener: (() -> Unit)? = null
        /** Invoked when the left snap option is clicked. */
        var onLeftSnapClickListener: (() -> Unit)? = null
        /** Invoked when the right snap option is clicked. */
        var onRightSnapClickListener: (() -> Unit)? = null
        /** Invoked whenever the hover state of the menu changes. */
        var onMenuHoverListener: ((Boolean) -> Unit)? = null

        init {
            overlay.setOnHoverListener { _, event ->
                // The overlay covers the entire menu, so it's a convenient way to monitor whether
                // the menu is hovered as a whole or not.
                when (event.action) {
                    ACTION_HOVER_ENTER -> onMenuHoverListener?.invoke(true)
                    ACTION_HOVER_EXIT -> onMenuHoverListener?.invoke(false)
                }

                // Also check if the hover falls within the snap options layout, to manually
                // set the left/right state based on the event's position.
                // TODO(b/346440693): this manual hover tracking is needed for left/right snap
                //  because its view/background(s) don't support selector states. Look into whether
                //  that can be added to avoid manual tracking. Also because these button
                //  colors/state logic is only being applied on hover events, but there's pressed,
                //  focused and selected states that should be responsive too.
                val snapLayoutBoundsRelToOverlay = hoverTempRect.also { rect ->
                    snapButtonsLayout.getDrawingRect(rect)
                    rootView.offsetDescendantRectToMyCoords(snapButtonsLayout, rect)
                }
                if (event.action == ACTION_HOVER_ENTER || event.action == ACTION_HOVER_MOVE) {
                    if (snapLayoutBoundsRelToOverlay.contains(event.x.toInt(), event.y.toInt())) {
                        // Hover is inside the snap layout, anything left of center is the left
                        // snap, and anything right of center is right snap.
                        val layoutCenter = snapLayoutBoundsRelToOverlay.centerX()
                        if (event.x < layoutCenter) {
                            updateSplitSnapSelection(SnapToHalfSelection.LEFT)
                        } else {
                            updateSplitSnapSelection(SnapToHalfSelection.RIGHT)
                        }
                    } else {
                        // Any other hover is outside the snap layout, so neither is selected.
                        updateSplitSnapSelection(SnapToHalfSelection.NONE)
                    }
                }

                // Don't consume the event to allow child views to receive the event too.
                return@setOnHoverListener false
            }

            maximizeButton.setOnClickListener { onMaximizeClickListener?.invoke() }
            snapRightButton.setOnClickListener { onRightSnapClickListener?.invoke() }
            snapLeftButton.setOnClickListener { onLeftSnapClickListener?.invoke() }

            // To prevent aliasing.
            maximizeButton.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            maximizeText.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        /** Bind the menu views to the new [RunningTaskInfo] data. */
        fun bind(taskInfo: RunningTaskInfo) {
            this.taskInfo = taskInfo
            this.style = calculateMenuStyle(taskInfo)

            rootView.background.setTint(style.backgroundColor)

            // Maximize option.
            maximizeButton.background = style.maximizeOption.drawable
            maximizeText.setTextColor(style.textColor)

            // Snap options.
            snapWindowText.setTextColor(style.textColor)
            updateSplitSnapSelection(SnapToHalfSelection.NONE)
        }

        /** Animate the opening of the menu */
        fun animateOpenMenu() {
            maximizeButton.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            maximizeText.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            openMenuAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(rootView, SCALE_Y, STARTING_MENU_HEIGHT_SCALE, 1f)
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
                            container.setPadding(menuPadding, topPadding,
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
                        maximizeButton.scaleY = value
                        snapButtonsLayout.scaleY = value
                        maximizeText.scaleY = value
                        snapWindowText.scaleY = value
                    }
                },
                ObjectAnimator.ofFloat(rootView, TRANSLATION_Y,
                    (STARTING_MENU_HEIGHT_SCALE - 1) * menuHeight, 0f).apply {
                    duration = MENU_HEIGHT_ANIMATION_DURATION_MS
                    interpolator = EMPHASIZED_DECELERATE
                },
                ObjectAnimator.ofInt(rootView.background, "alpha",
                    MAX_DRAWABLE_ALPHA_VALUE).apply {
                    duration = ALPHA_ANIMATION_DURATION_MS
                },
                ValueAnimator.ofFloat(0f, 1f)
                    .apply {
                        duration = ALPHA_ANIMATION_DURATION_MS
                        startDelay = CONTROLS_ALPHA_ANIMATION_DELAY_MS
                        addUpdateListener {
                            val value = animatedValue as Float
                            maximizeButton.alpha = value
                            snapButtonsLayout.alpha = value
                            maximizeText.alpha = value
                            snapWindowText.alpha = value
                        }
                    },
                ObjectAnimator.ofFloat(rootView, TRANSLATION_Z, MENU_Z_TRANSLATION)
                    .apply {
                        duration = ELEVATION_ANIMATION_DURATION_MS
                        startDelay = CONTROLS_ALPHA_ANIMATION_DELAY_MS
                    }
            )
            openMenuAnimatorSet.addListener(
                onEnd = {
                    maximizeButton.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    maximizeText.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
            )
            openMenuAnimatorSet.start()
        }

        /** Cancel the open menu animation. */
        fun cancelAnimation() {
            openMenuAnimatorSet.cancel()
        }

        /** Update the view state to a new snap to half selection. */
        private fun updateSplitSnapSelection(selection: SnapToHalfSelection) {
            when (selection) {
                SnapToHalfSelection.NONE -> deactivateSnapOptions()
                SnapToHalfSelection.LEFT -> activateSnapOption(activateLeft = true)
                SnapToHalfSelection.RIGHT -> activateSnapOption(activateLeft = false)
            }
        }

        private fun calculateMenuStyle(taskInfo: RunningTaskInfo): MenuStyle {
            val colorScheme = decorThemeUtil.getColorScheme(taskInfo)
            val menuBackgroundColor = colorScheme.surfaceContainerLow.toArgb()
            return MenuStyle(
                backgroundColor = menuBackgroundColor,
                textColor = colorScheme.onSurface.toArgb(),
                maximizeOption = MenuStyle.MaximizeOption(
                    drawable = createMaximizeDrawable(menuBackgroundColor, colorScheme)
                ),
                snapOptions = MenuStyle.SnapOptions(
                    inactiveSnapSideColor = colorScheme.outlineVariant.toArgb(),
                    semiActiveSnapSideColor = colorScheme.primary.toArgb().withAlpha(OPACITY_40),
                    activeSnapSideColor = colorScheme.primary.toArgb(),
                    inactiveStrokeColor = colorScheme.outlineVariant.toArgb(),
                    activeStrokeColor = colorScheme.primary.toArgb(),
                    inactiveBackgroundColor = menuBackgroundColor,
                    activeBackgroundColor = colorScheme.primary.toArgb().withAlpha(OPACITY_12)
                ),
            )
        }

        private fun deactivateSnapOptions() {
            // TODO(b/346440693): the background/colorStateList set on these buttons is overridden
            //  to a static resource & color on manually tracked hover events, which defeats the
            //  point of state lists and selector states. Look into whether changing that is
            //  possible, similar to the maximize option. Also to include support for the
            //  semi-active state (when the "other" snap option is selected).
            val snapSideColorList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(),
                ),
                intArrayOf(
                    style.snapOptions.activeSnapSideColor,
                    style.snapOptions.activeSnapSideColor,
                    style.snapOptions.activeSnapSideColor,
                    style.snapOptions.inactiveSnapSideColor
                )
            )
            snapLeftButton.background?.setTintList(snapSideColorList)
            snapRightButton.background?.setTintList(snapSideColorList)
            with (snapButtonsLayout) {
                setBackgroundResource(R.drawable.desktop_mode_maximize_menu_layout_background)
                (background as GradientDrawable).apply {
                    setColor(style.snapOptions.inactiveBackgroundColor)
                    setStroke(outlineStroke, style.snapOptions.inactiveStrokeColor)
                }
            }
        }

        private fun activateSnapOption(activateLeft: Boolean) {
            // Regardless of which side is active, the background of the snap options layout (that
            // includes both sides) is considered "active".
            with (snapButtonsLayout) {
                setBackgroundResource(
                    R.drawable.desktop_mode_maximize_menu_layout_background_on_hover)
                (background as GradientDrawable).apply {
                    setColor(style.snapOptions.activeBackgroundColor)
                    setStroke(outlineStroke, style.snapOptions.activeStrokeColor)
                }
            }
            if (activateLeft) {
                // Highlight snap left button, partially highlight the other side.
                snapLeftButton.background.setTint(style.snapOptions.activeSnapSideColor)
                snapRightButton.background.setTint(style.snapOptions.semiActiveSnapSideColor)
            } else {
                // Highlight snap right button, partially highlight the other side.
                snapRightButton.background.setTint(style.snapOptions.activeSnapSideColor)
                snapLeftButton.background.setTint(style.snapOptions.semiActiveSnapSideColor)
            }
        }

        private fun createMaximizeDrawable(
            @ColorInt menuBackgroundColor: Int,
            colorScheme: ColorScheme
        ): StateListDrawable {
            val activeStrokeAndFill = colorScheme.primary.toArgb()
            val activeBackground = colorScheme.primary.toArgb().withAlpha(OPACITY_12)
            val activeDrawable = createMaximizeButtonDrawable(
                strokeAndFillColor = activeStrokeAndFill,
                backgroundColor = activeBackground,
                // Add a mask with the menu background's color because the active background color is
                // semi transparent, otherwise the transparency will reveal the stroke/fill color
                // behind it.
                backgroundMask = menuBackgroundColor
            )
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), activeDrawable)
                addState(intArrayOf(android.R.attr.state_focused), activeDrawable)
                addState(intArrayOf(android.R.attr.state_selected), activeDrawable)
                addState(intArrayOf(android.R.attr.state_hovered), activeDrawable)
                // Inactive drawable.
                addState(
                    StateSet.WILD_CARD,
                    createMaximizeButtonDrawable(
                        strokeAndFillColor = colorScheme.outlineVariant.toArgb(),
                        backgroundColor = colorScheme.surfaceContainerLow.toArgb(),
                        backgroundMask = null // not needed because the bg color is fully opaque
                    )
                )
            }
        }

        private fun createMaximizeButtonDrawable(
            @ColorInt strokeAndFillColor: Int,
            @ColorInt backgroundColor: Int,
            @ColorInt backgroundMask: Int?
        ): LayerDrawable {
            val layers = mutableListOf<Drawable>()
            // First (bottom) layer, effectively the button's border ring once its inner shape is
            // covered by the next layers.
            layers.add(ShapeDrawable().apply {
                shape = RoundRectShape(
                    FloatArray(8) { outlineRadius.toFloat() },
                    null /* inset */,
                    null /* innerRadii */
                )
                paint.color = strokeAndFillColor
                paint.style = Paint.Style.FILL
            })
            // Second layer, a mask for the next (background) layer if needed because of
            // transparency.
            backgroundMask?.let { color ->
                layers.add(
                    ShapeDrawable().apply {
                        shape = RoundRectShape(
                            FloatArray(8) { outlineRadius.toFloat() },
                            null /* inset */,
                            null /* innerRadii */
                        )
                        paint.color = color
                        paint.style = Paint.Style.FILL
                    }
                )
            }
            // Third layer, the "background" padding between the border and the fill.
            layers.add(ShapeDrawable().apply {
                shape = RoundRectShape(
                    FloatArray(8) { outlineRadius.toFloat() },
                    null /* inset */,
                    null /* innerRadii */
                )
                paint.color = backgroundColor
                paint.style = Paint.Style.FILL
            })
            // Final layer, the inner most rounded-rect "fill".
            layers.add(ShapeDrawable().apply {
                shape = RoundRectShape(
                    FloatArray(8) { fillRadius.toFloat() },
                    null /* inset */,
                    null /* innerRadii */
                )
                paint.color = strokeAndFillColor
                paint.style = Paint.Style.FILL
            })
            return LayerDrawable(layers.toTypedArray()).apply {
                when (numberOfLayers) {
                    3 -> {
                        setLayerInset(1, outlineStroke)
                        setLayerInset(2, fillPadding)
                    }
                    4 -> {
                        setLayerInset(intArrayOf(1, 2), outlineStroke)
                        setLayerInset(3, fillPadding)
                    }
                    else -> error("Unexpected number of layers: $numberOfLayers")
                }
            }
        }

        private fun LayerDrawable.setLayerInset(index: IntArray, inset: Int) {
            for (i in index) {
                setLayerInset(i, inset, inset, inset, inset)
            }
        }

        private fun LayerDrawable.setLayerInset(index: Int, inset: Int) {
            setLayerInset(index, inset, inset, inset, inset)
        }

        private fun requireViewById(id: Int) = rootView.requireViewById<View>(id)

        /** The style to apply to the menu. */
        data class MenuStyle(
            @ColorInt val backgroundColor: Int,
            @ColorInt val textColor: Int,
            val maximizeOption: MaximizeOption,
            val snapOptions: SnapOptions,
        ) {
            data class MaximizeOption(
                val drawable: StateListDrawable,
            )
            data class SnapOptions(
                @ColorInt val inactiveSnapSideColor: Int,
                @ColorInt val semiActiveSnapSideColor: Int,
                @ColorInt val activeSnapSideColor: Int,
                @ColorInt val inactiveStrokeColor: Int,
                @ColorInt val activeStrokeColor: Int,
                @ColorInt val inactiveBackgroundColor: Int,
                @ColorInt val activeBackgroundColor: Int,
            )
        }

        /** The possible selection states of the half-snap menu option. */
        enum class SnapToHalfSelection {
            NONE, LEFT, RIGHT
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
    }
}

/** A factory interface to create a [MaximizeMenu]. */
interface MaximizeMenuFactory {
    fun create(
        syncQueue: SyncTransactionQueue,
        rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
        displayController: DisplayController,
        taskInfo: RunningTaskInfo,
        decorWindowContext: Context,
        menuPosition: PointF,
        transactionSupplier: Supplier<Transaction>
    ): MaximizeMenu
}

/** A [MaximizeMenuFactory] implementation that creates a [MaximizeMenu].  */
object DefaultMaximizeMenuFactory : MaximizeMenuFactory {
    override fun create(
        syncQueue: SyncTransactionQueue,
        rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
        displayController: DisplayController,
        taskInfo: RunningTaskInfo,
        decorWindowContext: Context,
        menuPosition: PointF,
        transactionSupplier: Supplier<Transaction>
    ): MaximizeMenu {
        return MaximizeMenu(
            syncQueue,
            rootTdaOrganizer,
            displayController,
            taskInfo,
            decorWindowContext,
            menuPosition,
            transactionSupplier
        )
    }
}
