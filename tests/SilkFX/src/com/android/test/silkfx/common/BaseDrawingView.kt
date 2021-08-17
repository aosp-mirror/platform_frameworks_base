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

package com.android.test.silkfx.common

import android.content.Context
import android.graphics.Color
import android.graphics.ColorSpace
import android.util.AttributeSet
import android.view.View

open class BaseDrawingView : View {
    val scRGB = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
    val bt2020 = ColorSpace.get(ColorSpace.Named.BT2020)
    val lab = ColorSpace.get(ColorSpace.Named.CIE_LAB)

    val density: Float
    val dp: Int.() -> Float

    fun color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Long {
        return Color.pack(red, green, blue, alpha, scRGB)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setWillNotDraw(false)
        isClickable = true
        density = resources.displayMetrics.density
        dp = { this * density }
    }
}