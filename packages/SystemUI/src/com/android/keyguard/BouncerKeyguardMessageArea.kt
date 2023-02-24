/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.graphics.Color
import android.util.AttributeSet
import com.android.settingslib.Utils

/** Displays security messages for the keyguard bouncer. */
class BouncerKeyguardMessageArea(context: Context?, attrs: AttributeSet?) :
    KeyguardMessageArea(context, attrs) {
    private val DEFAULT_COLOR = -1
    private var mDefaultColorState: ColorStateList? = null
    private var mNextMessageColorState: ColorStateList? = ColorStateList.valueOf(DEFAULT_COLOR)

    override fun updateTextColor() {
        var colorState = mDefaultColorState
        mNextMessageColorState?.defaultColor?.let { color ->
            if (color != DEFAULT_COLOR) {
                colorState = mNextMessageColorState
                mNextMessageColorState = ColorStateList.valueOf(DEFAULT_COLOR)
            }
        }
        setTextColor(colorState)
    }

    override fun setNextMessageColor(colorState: ColorStateList?) {
        mNextMessageColorState = colorState
    }

    override fun onThemeChanged() {
        val array: TypedArray =
            mContext.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val newTextColors: ColorStateList = ColorStateList.valueOf(array.getColor(0, Color.RED))
        array.recycle()
        mDefaultColorState = newTextColors
        super.onThemeChanged()
    }

    override fun reloadColor() {
        mDefaultColorState = Utils.getColorAttr(context, android.R.attr.textColorPrimary)
        super.reloadColor()
    }
}
