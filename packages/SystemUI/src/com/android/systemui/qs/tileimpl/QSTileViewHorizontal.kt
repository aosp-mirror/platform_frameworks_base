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
import android.graphics.drawable.RippleDrawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileImpl.getColorForState

open class QSTileViewHorizontal(
    context: Context,
    icon: QSIconView,
    collapsed: Boolean
) : QSTileView(context, icon, collapsed), HeightOverrideable {

    protected var colorBackgroundDrawable: Drawable? = null
    private var paintColor = Color.WHITE
    private var paintAnimator: ValueAnimator? = null
    private var labelAnimator: ValueAnimator? = null
    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        mDualTargetAllowed = false
        val padding = context.resources.getDimensionPixelSize(R.dimen.qs_tile_side_label_padding)
        setPadding(padding, paddingTop, padding, paddingBottom)

        mBg.setImageDrawable(null)
        mIconFrame.removeAllViews()
        removeView(mIconFrame)
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        addView(mIcon, 0, LayoutParams(iconSize, iconSize))

        mColorLabelActive = ColorStateList.valueOf(getColorForState(getContext(), STATE_ACTIVE))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (heightOverride != HeightOverrideable.NO_OVERRIDE) {
            bottom = top + heightOverride
        }
    }

    override fun createLabel() {
        super.createLabel()
        findViewById<LinearLayout>(R.id.label_group)?.apply {
            gravity = Gravity.START
            (layoutParams as? RelativeLayout.LayoutParams)?.apply {
                removeRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        }
        mLabelContainer.setPadding(0, 0, 0, 0)
        (mLabelContainer.layoutParams as MarginLayoutParams).apply {
            marginStart = context.resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        }
        mLabel.gravity = Gravity.START
        mLabel.textDirection = TEXT_DIRECTION_LOCALE
        mSecondLine.gravity = Gravity.START
        mSecondLine.textDirection = TEXT_DIRECTION_LOCALE

        (mLabelContainer.layoutParams as LayoutParams).gravity =
            Gravity.CENTER_VERTICAL or Gravity.START
        if (mCollapsedView) {
            mSecondLine.visibility = GONE
        }
    }

    override fun shouldLabelBeSingleLine(): Boolean {
        return true
    }

    override fun updateRippleSize() {
    }

    override fun newTileBackground(): Drawable? {
        val ripple = mContext.getDrawable(R.drawable.qs_tile_background) as RippleDrawable
        colorBackgroundDrawable = ripple.findDrawableByLayerId(R.id.background)
        return ripple
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        background = if (clickable && mShowRippleEffect) {
            mTileBackground
        } else {
            colorBackgroundDrawable
        }
    }

    override fun handleStateChanged(state: QSTile.State) {
        super.handleStateChanged(state)
        mLabelContainer.background = null

        val allowAnimations = animationsEnabled() && paintColor != Color.WHITE
        val newColor = getCircleColor(state.state)
        if (allowAnimations) {
            animateBackground(newColor)
        } else {
            if (newColor != paintColor) {
                clearBackgroundAnimator()
                colorBackgroundDrawable?.setTintList(ColorStateList.valueOf(newColor))?.also {
                    paintColor = newColor
                }
                paintColor = newColor
            }
        }
    }

    private fun animateBackground(newBackgroundColor: Int) {
        if (newBackgroundColor != paintColor) {
            clearBackgroundAnimator()
            paintAnimator = ValueAnimator.ofArgb(paintColor, newBackgroundColor)
                .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                    addUpdateListener { animation: ValueAnimator ->
                        val c = animation.animatedValue as Int
                        colorBackgroundDrawable?.setTintList(ColorStateList.valueOf(c))?.also {
                            paintColor = c
                        }
                    }
                    start()
                }
        }
    }

    override fun changeLabelColor(color: ColorStateList) {
        val allowAnimations = animationsEnabled()
        val currentColor = mLabel.textColors.defaultColor
        if (currentColor != color.defaultColor) {
            clearLabelAnimator()
            if (allowAnimations) {
                labelAnimator = ValueAnimator.ofArgb(currentColor, color.defaultColor)
                    .setDuration(QSIconViewImpl.QS_ANIM_LENGTH).apply {
                        addUpdateListener {
                            setLabelsColor(ColorStateList.valueOf(it.animatedValue as Int))
                        }
                        start()
                }
            } else {
                setLabelsColor(color)
            }
        }
    }

    private fun setLabelsColor(color: ColorStateList) {
        mLabel.setTextColor(color)
        if (!mCollapsedView) {
            mSecondLine.setTextColor(color)
        }
    }

    private fun clearBackgroundAnimator() {
        paintAnimator?.cancel()?.also { paintAnimator = null }
    }

    private fun clearLabelAnimator() {
        labelAnimator?.cancel()?.also { labelAnimator = null }
    }

    override fun handleExpand(dualTarget: Boolean) {}
}