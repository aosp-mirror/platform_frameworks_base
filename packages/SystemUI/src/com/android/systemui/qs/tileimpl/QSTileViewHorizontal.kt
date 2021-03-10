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

package com.android.systemui.qs.tileimpl

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileImpl.getColorForState

// Placeholder
private const val CORNER_RADIUS = 40f
private val RADII = (1..8).map { CORNER_RADIUS }.toFloatArray()

open class QSTileViewHorizontal(
    context: Context,
    icon: QSIconView
) : QSTileView(context, icon, false) {

    protected var backgroundDrawable: ShapeDrawable? = null
    private var paintColor = Color.WHITE
    private var paintAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        mDualTargetAllowed = false
        mBg.setImageDrawable(null)
        mColorLabelActive = ColorStateList.valueOf(getColorForState(getContext(), STATE_ACTIVE))
        mMaxLabelLines = 3
    }

    override fun createLabel() {
        super.createLabel()
        findViewById<LinearLayout>(R.id.label_group)?.apply {
            gravity = Gravity.START
            (layoutParams as? RelativeLayout.LayoutParams)?.apply {
                removeRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        }
        mLabel.gravity = Gravity.START
        mLabel.textDirection = TEXT_DIRECTION_LOCALE
        mSecondLine.gravity = Gravity.START
        mSecondLine.textDirection = TEXT_DIRECTION_LOCALE
        val padding = context.resources.getDimensionPixelSize(R.dimen.qs_tile_side_label_padding)
        mLabelContainer.setPaddingRelative(0, padding, padding, padding)
        (mLabelContainer.layoutParams as LayoutParams).gravity =
            Gravity.CENTER_VERTICAL or Gravity.START
    }

    override fun updateRippleSize() {
    }

    override fun newTileBackground(): Drawable? {
        backgroundDrawable = ShapeDrawable(RoundRectShape(RADII, null, null))
        return backgroundDrawable
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        background = mTileBackground
    }

    override fun handleStateChanged(state: QSTile.State) {
        super.handleStateChanged(state)
        mSecondLine.setTextColor(mLabel.textColors)
        mLabelContainer.background = null

        val allowAnimations = animationsEnabled() && paintColor != Color.WHITE
        val newColor = getCircleColor(state.state)
        if (allowAnimations) {
            animateToNewState(newColor)
        } else {
            if (newColor != paintColor) {
                clearAnimator()
                backgroundDrawable?.setTintList(ColorStateList.valueOf(newColor))
                paintColor = newColor
            }
        }
    }

    private fun animateToNewState(newColor: Int) {
        if (newColor != paintColor) {
            clearAnimator()
            paintAnimator = ValueAnimator.ofArgb(paintColor, newColor)
                .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                    addUpdateListener { animation: ValueAnimator ->
                        val c = animation.animatedValue as Int
                        backgroundDrawable?.setTintList(ColorStateList.valueOf(c))
                        paintColor = c
                    }
                    start()
                }
        }
    }

    private fun clearAnimator() {
        paintAnimator?.cancel()?.also { paintAnimator = null }
    }

    override fun handleExpand(dualTarget: Boolean) {}
}