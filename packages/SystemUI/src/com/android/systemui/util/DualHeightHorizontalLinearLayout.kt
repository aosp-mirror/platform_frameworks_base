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

package com.android.systemui.util

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R

/**
 * Horizontal [LinearLayout] to contain some text.
 *
 * The height of this container can alternate between two different heights, depending on whether
 * the text takes one line or more.
 *
 * When the text takes multiple lines, it will use the values in the regular attributes (`padding`,
 * `layout_height`). The single line behavior must be set in XML.
 *
 * XML attributes for single line behavior:
 * * `systemui:textViewId`: set the id for the [TextView] that determines the height of the
 *   container
 * * `systemui:singleLineHeight`: sets the height of the view when the text takes up only one line.
 *   By default, it will use [getMinimumHeight].
 * * `systemui:singleLineVerticalPadding`: sets the padding (top and bottom) when then text takes up
 * only one line. By default, it is 0.
 *
 * All dimensions are updated when configuration changes.
 */
class DualHeightHorizontalLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val singleLineHeightValue: TypedValue?
    private var singleLineHeightPx = 0

    private val singleLineVerticalPaddingValue: TypedValue?
    private var singleLineVerticalPaddingPx = 0

    private val textViewId: Int
    private var textView: TextView? = null

    private val displayMetrics: DisplayMetrics
        get() = context.resources.displayMetrics

    private var initialPadding = mPaddingTop // All vertical padding is the same

    init {
        if (orientation != HORIZONTAL) {
            throw IllegalStateException("This view should always have horizontal orientation")
        }

        val ta = context.obtainStyledAttributes(
                attrs,
                R.styleable.DualHeightHorizontalLinearLayout, defStyleAttrs, defStyleRes
        )

        val tempHeight = TypedValue()
        singleLineHeightValue = if (
                ta.hasValue(R.styleable.DualHeightHorizontalLinearLayout_singleLineHeight)
        ) {
            ta.getValue(R.styleable.DualHeightHorizontalLinearLayout_singleLineHeight, tempHeight)
            tempHeight
        } else {
            null
        }

        val tempPadding = TypedValue()
        singleLineVerticalPaddingValue = if (
                ta.hasValue(R.styleable.DualHeightHorizontalLinearLayout_singleLineVerticalPadding)
        ) {
            ta.getValue(
                    R.styleable.DualHeightHorizontalLinearLayout_singleLineVerticalPadding,
                    tempPadding
            )
            tempPadding
        } else {
            null
        }

        textViewId = ta.getResourceId(R.styleable.DualHeightHorizontalLinearLayout_textViewId, 0)

        ta.recycle()
    }

    init {
        updateResources()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        initialPadding = top
    }

    override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
        super.setPaddingRelative(start, top, end, bottom)
        initialPadding = top
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        textView?.let { tv ->
            if (tv.lineCount < 2) {
                setMeasuredDimension(measuredWidth, singleLineHeightPx)
                mPaddingBottom = 0
                mPaddingTop = 0
            } else {
                mPaddingBottom = initialPadding
                mPaddingTop = initialPadding
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        textView = findViewById(textViewId)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun setOrientation(orientation: Int) {
        if (orientation == VERTICAL) {
            throw IllegalStateException("This view should always have horizontal orientation")
        }
        super.setOrientation(orientation)
    }

    private fun updateResources() {
        updateDimensionValue(singleLineHeightValue, minimumHeight, ::singleLineHeightPx::set)
        updateDimensionValue(singleLineVerticalPaddingValue, 0, ::singleLineVerticalPaddingPx::set)
    }

    private inline fun updateDimensionValue(
        tv: TypedValue?,
        defaultValue: Int,
        propertySetter: (Int) -> Unit
    ) {
        val value = tv?.let {
            if (it.resourceId != 0) {
                context.resources.getDimensionPixelSize(it.resourceId)
            } else {
                it.getDimension(displayMetrics).toInt()
            }
        } ?: defaultValue
        propertySetter(value)
    }
}