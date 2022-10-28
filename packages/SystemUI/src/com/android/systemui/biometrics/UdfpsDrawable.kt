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
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.util.PathParser
import com.android.systemui.R

private const val DEFAULT_STROKE_WIDTH = 3f

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
abstract class UdfpsDrawable(
    protected val context: Context,
    drawableFactory: (Context) -> ShapeDrawable
) : Drawable() {

    constructor(context: Context) : this(context, defaultFactory)

    /** Fingerprint affordance. */
    val fingerprintDrawable: ShapeDrawable = drawableFactory(context)

    private var _alpha: Int = 255 // 0 - 255

    var strokeWidth: Float = fingerprintDrawable.paint.strokeWidth
        set(value) {
            field = value
            fingerprintDrawable.paint.strokeWidth = value
            invalidateSelf()
        }

    var isDisplayConfigured: Boolean = false
        set(showing) {
            if (field == showing) {
                return
            }
            field = showing
            invalidateSelf()
        }

    /** The [sensorRect] coordinates for the sensor area. */
    open fun onSensorRectUpdated(sensorRect: RectF) {
        val margin = sensorRect.height().toInt() / 8
        val bounds = Rect(
            sensorRect.left.toInt() + margin,
            sensorRect.top.toInt() + margin,
            sensorRect.right.toInt() - margin,
            sensorRect.bottom.toInt() - margin
        )
        updateFingerprintIconBounds(bounds)
    }

    /** Bounds for the fingerprint icon. */
    protected open fun updateFingerprintIconBounds(bounds: Rect) {
        fingerprintDrawable.bounds = bounds
        invalidateSelf()
    }

    override fun getAlpha(): Int = _alpha

    override fun setAlpha(alpha: Int) {
        _alpha = alpha
        fingerprintDrawable.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun getOpacity(): Int = 0
}

private val defaultFactory = { context: Context ->
    val fpPath = context.resources.getString(R.string.config_udfpsIcon)
    val drawable = ShapeDrawable(
        PathShape(PathParser.createPathFromPathData(fpPath), 72f, 72f)
    )
    drawable.mutate()
    drawable.paint.style = Paint.Style.STROKE
    drawable.paint.strokeCap = Paint.Cap.ROUND
    drawable.paint.strokeWidth = DEFAULT_STROKE_WIDTH
    drawable
}
