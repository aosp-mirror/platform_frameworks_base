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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import android.widget.TextView
import com.android.systemui.R

/**
 * View for showing a date that can toggle between two different formats depending on size.
 *
 * If no pattern can fit, it will display empty.
 *
 * @see R.styleable.VariableDateView_longDatePattern
 * @see R.styleable.VariableDateView_shortDatePattern
 */
class VariableDateView(context: Context, attrs: AttributeSet) : TextView(context, attrs) {

    val longerPattern: String
    val shorterPattern: String

    init {
        val a = context.theme.obtainStyledAttributes(
                attrs,
                R.styleable.VariableDateView,
                0, 0)
        longerPattern = a.getString(R.styleable.VariableDateView_longDatePattern)
                ?: context.getString(R.string.system_ui_date_pattern)
        shorterPattern = a.getString(R.styleable.VariableDateView_shortDatePattern)
                ?: context.getString(R.string.abbrev_month_day_no_year)

        a.recycle()
    }

    /**
     * Freeze the pattern switching
     *
     * Use during animations if the container will change its size but this view should not change
     */
    var freezeSwitching = false

    private var onMeasureListener: OnMeasureListener? = null

    fun onAttach(listener: OnMeasureListener?) {
        onMeasureListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && !freezeSwitching) {
            onMeasureListener?.onMeasureAction(availableWidth)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun getDesiredWidthForText(text: CharSequence): Float {
        return StaticLayout.getDesiredWidth(text, paint)
    }

    interface OnMeasureListener {
        fun onMeasureAction(availableWidth: Int)
    }
}