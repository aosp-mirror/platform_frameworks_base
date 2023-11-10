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
 *
 */

package com.android.systemui.keyboard.backlight.ui.view

import android.annotation.AttrRes
import android.annotation.ColorInt
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import androidx.annotation.IdRes
import androidx.core.view.setPadding
import com.android.settingslib.Utils
import com.android.systemui.res.R

class KeyboardBacklightDialog(
    context: Context,
    initialCurrentLevel: Int,
    initialMaxLevel: Int,
    theme: Int = R.style.Theme_SystemUI_Dialog,
) : Dialog(context, theme) {

    private data class RootProperties(
        val cornerRadius: Float,
        val verticalPadding: Int,
        val horizontalPadding: Int,
    )

    private data class BacklightIconProperties(
        val width: Int,
        val height: Int,
        val padding: Int,
    )

    private data class StepViewProperties(
        val width: Int,
        val height: Int,
        val horizontalMargin: Int,
        val smallRadius: Float,
        val largeRadius: Float,
    )

    private var currentLevel: Int = 0
    private var maxLevel: Int = 0

    private lateinit var rootView: LinearLayout

    private var dialogBottomMargin = 208
    private lateinit var rootProperties: RootProperties
    private lateinit var iconProperties: BacklightIconProperties
    private lateinit var stepProperties: StepViewProperties

    @ColorInt
    private val filledRectangleColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorPrimary)
    @ColorInt
    private val emptyRectangleColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorOutlineVariant)
    @ColorInt
    private val backgroundColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorSurfaceBright)
    @ColorInt
    private val defaultIconColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorOnPrimary)
    @ColorInt
    private val defaultIconBackgroundColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorPrimary)
    @ColorInt
    private val dimmedIconColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorOnSurface)
    @ColorInt
    private val dimmedIconBackgroundColor =
        getColorFromStyle(com.android.internal.R.attr.materialColorSurfaceDim)

    private val levelContentDescription = context.getString(R.string.keyboard_backlight_value)

    init {
        currentLevel = initialCurrentLevel
        maxLevel = initialMaxLevel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setUpWindowProperties(this)
        setWindowPosition()
        // title is used for a11y announcement
        window?.setTitle(context.getString(R.string.keyboard_backlight_dialog_title))
        updateResources()
        rootView = buildRootView()
        setContentView(rootView)
        super.onCreate(savedInstanceState)
        updateState(currentLevel, maxLevel, forceRefresh = true)
    }

    private fun updateResources() {
        context.resources.apply {
            rootProperties =
                RootProperties(
                    cornerRadius =
                        getDimensionPixelSize(R.dimen.backlight_indicator_root_corner_radius)
                            .toFloat(),
                    verticalPadding =
                        getDimensionPixelSize(R.dimen.backlight_indicator_root_vertical_padding),
                    horizontalPadding =
                        getDimensionPixelSize(R.dimen.backlight_indicator_root_horizontal_padding)
                )
            iconProperties =
                BacklightIconProperties(
                    width = getDimensionPixelSize(R.dimen.backlight_indicator_icon_width),
                    height = getDimensionPixelSize(R.dimen.backlight_indicator_icon_height),
                    padding = getDimensionPixelSize(R.dimen.backlight_indicator_icon_padding),
                )
            stepProperties =
                StepViewProperties(
                    width = getDimensionPixelSize(R.dimen.backlight_indicator_step_width),
                    height = getDimensionPixelSize(R.dimen.backlight_indicator_step_height),
                    horizontalMargin =
                        getDimensionPixelSize(R.dimen.backlight_indicator_step_horizontal_margin),
                    smallRadius =
                        getDimensionPixelSize(R.dimen.backlight_indicator_step_small_radius)
                            .toFloat(),
                    largeRadius =
                        getDimensionPixelSize(R.dimen.backlight_indicator_step_large_radius)
                            .toFloat(),
                )
        }
    }

    @ColorInt
    fun getColorFromStyle(@AttrRes colorId: Int): Int {
        return Utils.getColorAttrDefaultColor(context, colorId)
    }

    fun updateState(current: Int, max: Int, forceRefresh: Boolean = false) {
        if (maxLevel != max || forceRefresh) {
            maxLevel = max
            rootView.removeAllViews()
            rootView.addView(buildIconTile())
            buildStepViews().forEach { rootView.addView(it) }
        }
        currentLevel = current
        updateIconTile()
        updateStepColors()
        updateAccessibilityInfo()
    }

    private fun updateAccessibilityInfo() {
        rootView.contentDescription = String.format(levelContentDescription, currentLevel, maxLevel)
        rootView.sendAccessibilityEvent(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)
    }

    private fun updateIconTile() {
        val iconTile = rootView.requireViewById(BACKLIGHT_ICON_ID) as ImageView
        val backgroundDrawable = iconTile.background as ShapeDrawable
        if (currentLevel == 0) {
            iconTile.setColorFilter(dimmedIconColor)
            updateColor(backgroundDrawable, dimmedIconBackgroundColor)
        } else {
            iconTile.setColorFilter(defaultIconColor)
            updateColor(backgroundDrawable, defaultIconBackgroundColor)
        }
    }

    private fun updateStepColors() {
        (1 until rootView.childCount).forEach { index ->
            val drawable = rootView.getChildAt(index).background as ShapeDrawable
            updateColor(
                drawable,
                if (index <= currentLevel) filledRectangleColor else emptyRectangleColor,
            )
        }
    }

    private fun updateColor(drawable: ShapeDrawable, @ColorInt color: Int) {
        if (drawable.paint.color != color) {
            drawable.paint.color = color
            drawable.invalidateSelf()
        }
    }

    private fun buildRootView(): LinearLayout {
        val linearLayout =
            LinearLayout(context).apply {
                id = R.id.keyboard_backlight_dialog_container
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                setPadding(
                    /* left= */ rootProperties.horizontalPadding,
                    /* top= */ rootProperties.verticalPadding,
                    /* right= */ rootProperties.horizontalPadding,
                    /* bottom= */ rootProperties.verticalPadding
                )
            }
        val drawable =
            ShapeDrawable(
                RoundRectShape(
                    /* outerRadii= */ FloatArray(8) { rootProperties.cornerRadius },
                    /* inset= */ null,
                    /* innerRadii= */ null
                )
            )
        drawable.paint.color = backgroundColor
        linearLayout.background = drawable
        return linearLayout
    }

    private fun buildStepViews(): List<FrameLayout> {
        return (1..maxLevel).map { i -> createStepViewAt(i) }
    }

    private fun buildIconTile(): View {
        val diameter = stepProperties.height
        val circleDrawable =
            ShapeDrawable(OvalShape()).apply {
                intrinsicHeight = diameter
                intrinsicWidth = diameter
            }

        return ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_backlight)
            id = BACKLIGHT_ICON_ID
            setColorFilter(defaultIconColor)
            setPadding(iconProperties.padding)
            layoutParams =
                MarginLayoutParams(diameter, diameter).apply {
                    setMargins(
                        /* left= */ stepProperties.horizontalMargin,
                        /* top= */ 0,
                        /* right= */ stepProperties.horizontalMargin,
                        /* bottom= */ 0
                    )
                }
            background = circleDrawable
        }
    }

    private fun createStepViewAt(i: Int): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams =
                FrameLayout.LayoutParams(stepProperties.width, stepProperties.height).apply {
                    setMargins(
                        /* left= */ stepProperties.horizontalMargin,
                        /* top= */ 0,
                        /* right= */ stepProperties.horizontalMargin,
                        /* bottom= */ 0
                    )
                }
            val drawable =
                ShapeDrawable(
                    RoundRectShape(
                        /* outerRadii= */ radiiForIndex(i, maxLevel),
                        /* inset= */ null,
                        /* innerRadii= */ null
                    )
                )
            drawable.paint.color = emptyRectangleColor
            background = drawable
        }
    }

    private fun setWindowPosition() {
        window?.apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            this.attributes =
                WindowManager.LayoutParams().apply {
                    copyFrom(attributes)
                    y = dialogBottomMargin
                }
        }
    }

    private fun setUpWindowProperties(dialog: Dialog) {
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE) // otherwise fails while creating actionBar
            setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes.title = "KeyboardBacklightDialog"
        }
        setCanceledOnTouchOutside(true)
    }

    private fun radiiForIndex(i: Int, last: Int): FloatArray {
        val smallRadius = stepProperties.smallRadius
        val largeRadius = stepProperties.largeRadius
        val radii = FloatArray(8) { smallRadius }
        if (i == 1) {
            radii.setLeftCorners(largeRadius)
        }
        // note "first" and "last" might be the same tile
        if (i == last) {
            radii.setRightCorners(largeRadius)
        }
        return radii
    }

    private fun FloatArray.setLeftCorners(radius: Float) {
        LEFT_CORNERS_INDICES.forEach { this[it] = radius }
    }
    private fun FloatArray.setRightCorners(radius: Float) {
        RIGHT_CORNERS_INDICES.forEach { this[it] = radius }
    }

    private companion object {
        @IdRes val BACKLIGHT_ICON_ID = R.id.backlight_icon

        // indices used to define corners radii in ShapeDrawable
        val LEFT_CORNERS_INDICES: IntArray = intArrayOf(0, 1, 6, 7)
        val RIGHT_CORNERS_INDICES: IntArray = intArrayOf(2, 3, 4, 5)
    }
}
