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

import android.annotation.ColorInt
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import com.android.systemui.R
import com.android.systemui.util.children

class KeyboardBacklightDialog(
    context: Context,
    initialCurrentLevel: Int,
    initialMaxLevel: Int,
) : Dialog(context) {

    private data class RootProperties(
        val cornerRadius: Float,
        val verticalPadding: Int,
        val horizontalPadding: Int,
    )

    private data class BacklightIconProperties(
        val width: Int,
        val height: Int,
        val leftMargin: Int,
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
    @ColorInt var filledRectangleColor: Int = 0
    @ColorInt var emptyRectangleColor: Int = 0
    @ColorInt var backgroundColor: Int = 0

    init {
        currentLevel = initialCurrentLevel
        maxLevel = initialMaxLevel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setUpWindowProperties(this)
        setWindowTitle()
        updateResources()
        rootView = buildRootView()
        setContentView(rootView)
        super.onCreate(savedInstanceState)
        updateState(currentLevel, maxLevel, forceRefresh = true)
    }

    private fun updateResources() {
        context.resources.apply {
            filledRectangleColor = getColor(R.color.backlight_indicator_step_filled)
            emptyRectangleColor = getColor(R.color.backlight_indicator_step_empty)
            backgroundColor = getColor(R.color.backlight_indicator_background)
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
                    leftMargin =
                        getDimensionPixelSize(R.dimen.backlight_indicator_icon_left_margin),
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

    fun updateState(current: Int, max: Int, forceRefresh: Boolean = false) {
        if (maxLevel != max || forceRefresh) {
            maxLevel = max
            rootView.removeAllViews()
            buildStepViews().forEach { rootView.addView(it) }
        }
        currentLevel = current
        updateLevel()
    }

    private fun updateLevel() {
        rootView.children.forEachIndexed(
            action = { index, v ->
                val drawable = v.background as ShapeDrawable
                if (index <= currentLevel) {
                    updateColor(drawable, filledRectangleColor)
                } else {
                    updateColor(drawable, emptyRectangleColor)
                }
            }
        )
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
        val stepViews = (0..maxLevel).map { i -> createStepViewAt(i) }
        stepViews[0].addView(createBacklightIconView())
        return stepViews
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

    private fun createBacklightIconView(): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_backlight)
            layoutParams =
                FrameLayout.LayoutParams(iconProperties.width, iconProperties.height).apply {
                    gravity = Gravity.CENTER
                    leftMargin = iconProperties.leftMargin
                }
        }
    }

    private fun setWindowTitle() {
        val attrs = window.attributes
        // TODO(b/271796169): check if title needs to be a translatable resource.
        attrs.title = "KeyboardBacklightDialog"
        attrs?.y = dialogBottomMargin
        window.attributes = attrs
    }

    private fun setUpWindowProperties(dialog: Dialog) {
        val window = dialog.window
        window.requestFeature(Window.FEATURE_NO_TITLE) // otherwise fails while creating actionBar
        window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setCanceledOnTouchOutside(true)
    }

    private fun radiiForIndex(i: Int, last: Int): FloatArray {
        val smallRadius = stepProperties.smallRadius
        val largeRadius = stepProperties.largeRadius
        return when (i) {
            0 -> // left radii bigger
            floatArrayOf(
                    largeRadius,
                    largeRadius,
                    smallRadius,
                    smallRadius,
                    smallRadius,
                    smallRadius,
                    largeRadius,
                    largeRadius
                )
            last -> // right radii bigger
            floatArrayOf(
                    smallRadius,
                    smallRadius,
                    largeRadius,
                    largeRadius,
                    largeRadius,
                    largeRadius,
                    smallRadius,
                    smallRadius
                )
            else -> FloatArray(8) { smallRadius } // all radii equal
        }
    }
}
