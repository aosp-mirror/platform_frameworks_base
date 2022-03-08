/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles

import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.util.PathParser
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.android.wm.shell.R

class BubbleOverflow(
    private val context: Context,
    private val positioner: BubblePositioner
) : BubbleViewProvider {

    private lateinit var bitmap: Bitmap
    private lateinit var dotPath: Path

    private var dotColor = 0
    private var showDot = false
    private var overflowIconInset = 0

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var expandedView: BubbleExpandedView?
    private var overflowBtn: BadgedImageView?

    init {
        updateResources()
        expandedView = null
        overflowBtn = null
    }

    /** Call before use and again if cleanUpExpandedState was called.  */
    fun initialize(controller: BubbleController) {
        getExpandedView()?.initialize(controller, controller.stackView, true /* isOverflow */)
    }

    fun cleanUpExpandedState() {
        expandedView?.cleanUpExpandedState()
        expandedView = null
    }

    fun update() {
        updateResources()
        getExpandedView()?.applyThemeAttrs()
        // Apply inset and new style to fresh icon drawable.
        getIconView()?.setImageResource(R.drawable.bubble_ic_overflow_button)
        updateBtnTheme()
    }

    fun updateResources() {
        overflowIconInset = context.resources.getDimensionPixelSize(
                R.dimen.bubble_overflow_icon_inset)
        overflowBtn?.layoutParams = FrameLayout.LayoutParams(positioner.bubbleSize,
                positioner.bubbleSize)
        expandedView?.updateDimensions()
    }

    private fun updateBtnTheme() {
        val res = context.resources

        // Set overflow button accent color, dot color
        val typedValue = TypedValue()
        context.theme.resolveAttribute(com.android.internal.R.attr.colorAccentPrimary,
                typedValue, true)
        val colorAccent = res.getColor(typedValue.resourceId, null)
        dotColor = colorAccent

        val shapeColor = res.getColor(android.R.color.system_accent1_1000)
        overflowBtn?.drawable?.setTint(shapeColor)

        val iconFactory = BubbleIconFactory(context)

        // Update bitmap
        val fg = InsetDrawable(overflowBtn?.drawable, overflowIconInset)
        bitmap = iconFactory.createBadgedIconBitmap(AdaptiveIconDrawable(
                ColorDrawable(colorAccent), fg),
            null /* user */, true /* shrinkNonAdaptiveIcons */).icon

        // Update dot path
        dotPath = PathParser.createPathFromPathData(
            res.getString(com.android.internal.R.string.config_icon_mask))
        val scale = iconFactory.normalizer.getScale(iconView!!.drawable,
            null /* outBounds */, null /* path */, null /* outMaskShape */)
        val radius = BadgedImageView.DEFAULT_PATH_SIZE / 2f
        val matrix = Matrix()
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
            radius /* pivot y */)
        dotPath.transform(matrix)

        // Attach BubbleOverflow to BadgedImageView
        overflowBtn?.setRenderedBubble(this)
        overflowBtn?.removeDotSuppressionFlag(BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE)
    }

    fun setVisible(visible: Int) {
        overflowBtn?.visibility = visible
    }

    fun setShowDot(show: Boolean) {
        showDot = show
        overflowBtn?.updateDotVisibility(true /* animate */)
    }

    override fun getExpandedView(): BubbleExpandedView? {
        if (expandedView == null) {
            expandedView = inflater.inflate(R.layout.bubble_expanded_view,
                    null /* root */, false /* attachToRoot */) as BubbleExpandedView
            expandedView?.applyThemeAttrs()
            updateResources()
        }
        return expandedView
    }

    override fun getDotColor(): Int {
        return dotColor
    }

    override fun getAppBadge(): Bitmap? {
        return null
    }

    override fun getBubbleIcon(): Bitmap {
        return bitmap
    }

    override fun showDot(): Boolean {
        return showDot
    }

    override fun getDotPath(): Path? {
        return dotPath
    }

    override fun setTaskViewVisibility(visible: Boolean) {
        // Overflow does not have a TaskView.
    }

    override fun getIconView(): BadgedImageView? {
        if (overflowBtn == null) {
            overflowBtn = inflater.inflate(R.layout.bubble_overflow_button,
                    null /* root */, false /* attachToRoot */) as BadgedImageView
            overflowBtn?.initialize(positioner)
            overflowBtn?.contentDescription = context.resources.getString(
                    R.string.bubble_overflow_button_content_description)
            val bubbleSize = positioner.bubbleSize
            overflowBtn?.layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            updateBtnTheme()
        }
        return overflowBtn
    }

    override fun getKey(): String {
        return KEY
    }

    override fun getTaskId(): Int {
        return if (expandedView != null) expandedView!!.taskId else INVALID_TASK_ID
    }

    companion object {
        const val KEY = "Overflow"
    }
}