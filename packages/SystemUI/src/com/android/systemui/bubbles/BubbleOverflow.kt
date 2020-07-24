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

package com.android.systemui.bubbles

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
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.R

class BubbleOverflow(
    private val context: Context,
    private val stack: BubbleStackView
) : BubbleViewProvider {

    private var bitmap: Bitmap? = null
    private var dotPath: Path? = null
    private var bitmapSize = 0
    private var iconBitmapSize = 0
    private var dotColor = 0

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val expandedView: BubbleExpandedView = inflater
        .inflate(R.layout.bubble_expanded_view, null /* root */, false /* attachToRoot */)
            as BubbleExpandedView
    private val overflowBtn: BadgedImageView = inflater
        .inflate(R.layout.bubble_overflow_button, null /* root */, false /* attachToRoot */)
            as BadgedImageView
    init {
        updateResources()
        with(expandedView) {
            setOverflow(true)
            setStackView(stack)
            applyThemeAttrs()
        }
        with(overflowBtn) {
            setContentDescription(context.resources.getString(
                R.string.bubble_overflow_button_content_description))
            updateBtnTheme()
        }
    }

    fun update() {
        updateResources()
        expandedView.applyThemeAttrs()
        // Apply inset and new style to fresh icon drawable.
        overflowBtn.setImageResource(R.drawable.ic_bubble_overflow_button)
        updateBtnTheme()
    }

    fun updateResources() {
        bitmapSize = context.resources.getDimensionPixelSize(R.dimen.bubble_bitmap_size)
        iconBitmapSize = context.resources.getDimensionPixelSize(
                R.dimen.bubble_overflow_icon_bitmap_size)
        val bubbleSize = context.resources.getDimensionPixelSize(R.dimen.individual_bubble_size)
        overflowBtn.setLayoutParams(FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        expandedView.updateDimensions()
    }

    fun updateBtnTheme() {
        val res = context.resources

        // Set overflow button accent color, dot color
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)

        val colorAccent = res.getColor(typedValue.resourceId)
        overflowBtn.getDrawable()?.setTint(colorAccent)
        dotColor = colorAccent

        // Set button and activity background color
        val nightMode = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                == Configuration.UI_MODE_NIGHT_YES)
        val bg = ColorDrawable(res.getColor(
                if (nightMode) R.color.bubbles_dark else R.color.bubbles_light))

        // Set button icon
        val iconFactory = BubbleIconFactory(context)
        val fg = InsetDrawable(overflowBtn.getDrawable(),
                bitmapSize - iconBitmapSize /* inset */)
        bitmap = iconFactory.createBadgedIconBitmap(AdaptiveIconDrawable(bg, fg),
                null /* user */, true /* shrinkNonAdaptiveIcons */).icon

        // Set dot path
        dotPath = PathParser.createPathFromPathData(
                res.getString(com.android.internal.R.string.config_icon_mask))
        val scale = iconFactory.normalizer.getScale(overflowBtn.getDrawable(),
                null /* outBounds */, null /* path */, null /* outMaskShape */)
        val radius = BadgedImageView.DEFAULT_PATH_SIZE / 2f
        val matrix = Matrix()
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                radius /* pivot y */)
        dotPath?.transform(matrix)
        overflowBtn.setRenderedBubble(this)
    }

    fun setVisible(visible: Int) {
        overflowBtn.visibility = visible
    }

    override fun getExpandedView(): BubbleExpandedView? {
        return expandedView
    }

    override fun getDotColor(): Int {
        return dotColor
    }

    override fun getBadgedImage(): Bitmap? {
        return bitmap
    }

    override fun showDot(): Boolean {
        return false
    }

    override fun getDotPath(): Path? {
        return dotPath
    }

    override fun setContentVisibility(visible: Boolean) {
        expandedView.setContentVisibility(visible)
    }

    override fun getIconView(): View? {
        return overflowBtn
    }

    override fun getKey(): String {
        return KEY
    }

    override fun getDisplayId(): Int {
        return expandedView.virtualDisplayId
    }

    companion object {
        @JvmField val KEY = "Overflow"
    }
}