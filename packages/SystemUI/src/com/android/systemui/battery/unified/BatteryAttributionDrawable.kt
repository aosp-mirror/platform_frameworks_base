/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.battery.unified

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.Gravity
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A battery attribution is defined as a drawable that can display either alongside the percent text
 * or solely in the center of the battery frame.
 *
 * Attributions are given an explicit canvas of 18x8, or 6x6 depending on the display mode (centered
 * or right-aligned). The size is configured in [BatteryLayersDrawable] by changing this drawable
 * wrapper's bounds, and optionally setting the [gravity]
 */
@Suppress("RtlHardcoded")
class BatteryAttributionDrawable(dr: Drawable?) : DrawableWrapper(dr) {
    /** One of [CENTER, LEFT]. Note that number text does not RTL. */
    var gravity = Gravity.CENTER
        set(value) {
            field = value
            updateBoundsInner()
        }

    // Must be called if bounds change, gravity changes, or the wrapped drawable changes
    private fun updateBoundsInner() {
        val dr = drawable ?: return

        val hScale = bounds.width().toFloat() / dr.intrinsicWidth.toFloat()
        val vScale = bounds.height().toFloat() / dr.intrinsicHeight.toFloat()
        val scale = min(hScale, vScale)

        val dw = scale * dr.intrinsicWidth
        val dh = scale * dr.intrinsicHeight

        if (gravity == Gravity.CENTER) {
            val padLeft = (bounds.width() - dw) / 2
            val padTop = (bounds.height() - dh) / 2
            dr.setBounds(
                (bounds.left + padLeft).roundToInt(),
                (bounds.top + padTop).roundToInt(),
                (bounds.left + padLeft + dw).roundToInt(),
                (bounds.top + padTop + dh).roundToInt()
            )
        } else if (gravity == Gravity.LEFT) {
            dr.setBounds(
                bounds.left,
                bounds.top,
                ceil(bounds.left + dw).toInt(),
                ceil(bounds.top + dh).toInt()
            )
        }
    }

    override fun setDrawable(dr: Drawable?) {
        super.setDrawable(dr)
        updateBoundsInner()
    }

    override fun onBoundsChange(bounds: Rect) {
        updateBoundsInner()
    }

    /**
     * DrawableWrapper allows for a null constructor, but this method assumes that the drawable is
     * non-null. It is called by LayerDrawable on init, so we have to handle null here specifically
     */
    override fun getChangingConfigurations(): Int = drawable?.changingConfigurations ?: 0

    override fun draw(canvas: Canvas) {
        drawable?.draw(canvas)
    }

    // Deprecated, but needed for Drawable implementation
    override fun getOpacity() = PixelFormat.OPAQUE

    // We don't use this
    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}
}
