/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * [LinearLayout] that can ignore the last child for measuring.
 *
 * The view is measured as regularly, then if [ignoreLastView] is true:
 * * In [LinearLayout.VERTICAL] orientation, the height of the last view is subtracted from the
 * final measured height.
 * * In [LinearLayout.HORIZONTAL] orientation, the width of the last view is subtracted from the
 * final measured width.
 *
 * This allows to measure the view and position it where it should, without it amounting to the
 * total size (only in the direction of layout).
 */
class IgnorableChildLinearLayout @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attributeSet, defStyleAttr, defStyleRes) {

    var ignoreLastView = false

    /**
     * Forces [MeasureSpec.UNSPECIFIED] in the direction of layout
     */
    var forceUnspecifiedMeasure = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val actualWidthSpec = if (forceUnspecifiedMeasure && orientation == HORIZONTAL) {
            MeasureSpec.makeMeasureSpec(widthMeasureSpec, MeasureSpec.UNSPECIFIED)
        } else widthMeasureSpec

        val actualHeightSpec = if (forceUnspecifiedMeasure && orientation == VERTICAL) {
            MeasureSpec.makeMeasureSpec(heightMeasureSpec, MeasureSpec.UNSPECIFIED)
        } else heightMeasureSpec

        super.onMeasure(actualWidthSpec, actualHeightSpec)
        if (ignoreLastView && childCount > 0) {
            val lastView = getChildAt(childCount - 1)
            if (lastView.visibility != GONE) {
                val lp = lastView.layoutParams as MarginLayoutParams
                if (orientation == VERTICAL) {
                    val height = lastView.measuredHeight + lp.bottomMargin + lp.topMargin
                    setMeasuredDimension(measuredWidth, measuredHeight - height)
                } else {
                    val width = lastView.measuredWidth + lp.leftMargin + lp.rightMargin
                    setMeasuredDimension(measuredWidth - width, measuredHeight)
                }
            }
        }
    }
}