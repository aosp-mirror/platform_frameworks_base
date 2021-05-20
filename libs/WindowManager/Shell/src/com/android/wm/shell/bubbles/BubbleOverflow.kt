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
import android.content.res.Configuration
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

/**
 * The icon in the bubble overflow is scaled down, this is the percent of the normal bubble bitmap
 * size to use.
 */
const val ICON_BITMAP_SIZE_PERCENT = 0.46f

class BubbleOverflow(
    private val context: Context,
    private val positioner: BubblePositioner
) : BubbleViewProvider {

    private lateinit var bitmap: Bitmap
    private lateinit var dotPath: Path

    private var bitmapSize = 0
    private var iconBitmapSize = 0
    private var dotColor = 0
    private var showDot = false

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var expandedView: BubbleExpandedView?
    private var overflowBtn: BadgedImageView?

    init {
        updateResources()
        bitmapSize = positioner.bubbleBitmapSize
        iconBitmapSize = (bitmapSize * ICON_BITMAP_SIZE_PERCENT).toInt()
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
        bitmapSize = positioner.bubbleBitmapSize
        iconBitmapSize = (bitmapSize * ICON_BITMAP_SIZE_PERCENT).toInt()
        val bubbleSize = positioner.bubbleSize
        overflowBtn?.layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        expandedView?.updateDimensions()
    }

    private fun updateBtnTheme() {
        val res = context.resources

        // Set overflow button accent color, dot color
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        val colorAccent = res.getColor(typedValue.resourceId, null)
        overflowBtn?.drawable?.setTint(colorAccent)
        dotColor = colorAccent

        val iconFactory = BubbleIconFactory(context)

        // Update bitmap
        val nightMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            == Configuration.UI_MODE_NIGHT_YES)
        val bg = ColorDrawable(res.getColor(
            if (nightMode) R.color.bubbles_dark else R.color.bubbles_light, null))

        val fg = InsetDrawable(overflowBtn?.drawable,
            bitmapSize - iconBitmapSize /* inset */)
        bitmap = iconFactory.createBadgedIconBitmap(AdaptiveIconDrawable(bg, fg),
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

    override fun setExpandedContentAlpha(alpha: Float) {
        expandedView?.alpha = alpha
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