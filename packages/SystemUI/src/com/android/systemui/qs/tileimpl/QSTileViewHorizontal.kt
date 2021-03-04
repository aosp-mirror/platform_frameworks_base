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
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.RippleDrawable
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

open class QSTileViewHorizontal(
    context: Context,
    icon: QSIconView
) : QSTileView(context, icon, false) {

    protected var paintDrawable: PaintDrawable? = null
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
        val d = super.newTileBackground()
        if (paintDrawable == null) {
            paintDrawable = PaintDrawable(paintColor).apply {
                setCornerRadius(CORNER_RADIUS)
            }
        }
        if (d is RippleDrawable) {
            d.addLayer(paintDrawable)
            return d
        } else {
            return paintDrawable
        }
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        background = mTileBackground
        if (clickable && mShowRippleEffect) {
            mRipple?.setHotspotBounds(left, top, right, bottom)
        } else {
            mRipple?.setHotspotBounds(0, 0, 0, 0)
        }
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
                paintDrawable?.paint?.color = newColor
                paintDrawable?.invalidateSelf()
            }
        }
        paintColor = newColor
    }

    private fun animateToNewState(newColor: Int) {
        if (newColor != paintColor) {
            clearAnimator()
            paintAnimator = ValueAnimator.ofArgb(paintColor, newColor)
                .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                    addUpdateListener { animation: ValueAnimator ->
                        paintDrawable?.paint?.color = animation.animatedValue as Int
                        paintDrawable?.invalidateSelf()
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