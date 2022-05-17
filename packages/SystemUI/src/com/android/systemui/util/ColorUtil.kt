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
 * limitations under the License.
 */

package com.android.systemui.util

import android.content.res.TypedArray
import android.graphics.Color
import android.view.ContextThemeWrapper

/** Returns an ARGB color version of [color] at the given [alpha]. */
fun getColorWithAlpha(color: Int, alpha: Float): Int =
    Color.argb(
        (alpha * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )


/**
 * Returns the color provided at the specified {@param attrIndex} in {@param a} if it exists,
 * otherwise, returns the color from the private attribute {@param privAttrId}.
 */
fun getPrivateAttrColorIfUnset(
    ctw: ContextThemeWrapper, attrArray: TypedArray,
    attrIndex: Int, defColor: Int, privAttrId: Int
): Int {
    // If the index is specified, use that value
    var a = attrArray
    if (a.hasValue(attrIndex)) {
        return a.getColor(attrIndex, defColor)
    }

    // Otherwise fallback to the value of the private attribute
    val customAttrs = intArrayOf(privAttrId)
    a = ctw.obtainStyledAttributes(customAttrs)
    val color = a.getColor(0, defColor)
    a.recycle()
    return color
}
