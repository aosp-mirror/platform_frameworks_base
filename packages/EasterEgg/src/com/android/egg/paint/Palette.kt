/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.egg.paint

import android.graphics.Color

class Palette {
    var colors : IntArray
    var lightest = 0
    var darkest = 0

    /**
     * rough luminance calculation
     * https://www.w3.org/TR/AERT/#color-contrast
     */
    private fun lum(rgb: Int): Float {
        return (Color.red(rgb) * 299f + Color.green(rgb) * 587f + Color.blue(rgb) * 114f) / 1000f
    }

    /**
     * create a random evenly-spaced color palette
     * guaranteed to contrast!
     */
    fun randomize(S: Float, V: Float) {
        val hsv = floatArrayOf((Math.random() * 360f).toFloat(), S, V)
        val count = colors.size
        colors[0] = Color.HSVToColor(hsv)
        lightest = 0
        darkest = 0

        for (i in 0 until count) {
            hsv[0] = (hsv[0] + 360f / count).rem(360f)
            val color = Color.HSVToColor(hsv)
            colors[i] = color

            val lum = lum(colors[i])
            if (lum < lum(colors[darkest])) darkest = i
            if (lum > lum(colors[lightest])) lightest = i
        }
    }

    override fun toString() : String {
        val str = StringBuilder("Palette{ ")
        for (c in colors) {
            str.append(String.format("#%08x ", c))
        }
        str.append("}")
        return str.toString()
    }

    constructor(count: Int) {
        colors = IntArray(count)
        randomize(1f, 1f)
    }

    constructor(count: Int, S: Float, V: Float) {
        colors = IntArray(count)
        randomize(S, V)
    }

    constructor(_colors: IntArray) {
        colors = _colors
        for (i in 0 until colors.size) {
            val lum = lum(colors[i])
            if (lum < lum(colors[darkest])) darkest = i
            if (lum > lum(colors[lightest])) lightest = i
        }
    }
}