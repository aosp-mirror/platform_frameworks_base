/*
 * Copyright (C) 2020-2022 crDroid Android Project
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
package com.android.settingslib.graph

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.android.settingslib.R
import com.android.settingslib.Utils
import kotlin.math.min

class FullCircleBatteryDrawable(private val context: Context, frameColor: Int) : Drawable() {
    private val criticalLevel: Int
    private val warningString: String
    private val framePaint: Paint
    private val batteryPaint: Paint
    private val textPaint: Paint
    private val powerSavePaint: Paint
    private val colors: IntArray
    private val padding = Rect()
    private val frame = RectF()

    private var chargeColor: Int
    private var iconTint = Color.WHITE
    private var intrinsicWidth: Int
    private var intrinsicHeight: Int
    private var height = 0
    private var width = 0
    private var chargingAnimator: ValueAnimator? = null
    private var batteryAlpha: Int

    // Dual tone implies that battery level is a clipped overlay over top of the whole shape
    private var dualTone = false

    override fun getIntrinsicHeight() = intrinsicHeight

    override fun getIntrinsicWidth() = intrinsicWidth

    var charging = false
        set(value) {
            val previousCharging = charging
            field = value
            if (value) {
                if (!previousCharging) {
                    startChargingAnimation(ValueAnimator.INFINITE)
                }
            } else {
                cancelChargingAnimation()
                postInvalidate()
            }
        }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    var showPercent = false
        set(value) {
            field = value
            postInvalidate()
        }

    var batteryLevel = -1
        set(value) {
            field = value
            postInvalidate()
        }

    private fun startChargingAnimation(repeat: Int) {
        val alpha = batteryPaint.alpha
        chargingAnimator = ValueAnimator.ofInt(alpha, 0, alpha)
        chargingAnimator?.addUpdateListener {
            batteryAlpha = it.animatedValue as Int
            postInvalidate()
        }

        chargingAnimator?.addListener(object: AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                super.onAnimationCancel(animation)
                batteryAlpha = alpha
                postInvalidate()
                onAnimationEnd(animation)
            }

            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                chargingAnimator = null
            }
        })
        chargingAnimator?.repeatCount = repeat
        chargingAnimator?.duration = 4000
        chargingAnimator?.startDelay = 500
        chargingAnimator?.start()
    }

    private fun cancelChargingAnimation() {
        if (chargingAnimator != null) {
            chargingAnimator?.cancel()
        }
    }

    // an approximation of View.postInvalidate()
    private fun postInvalidate() {
        unscheduleSelf { invalidateSelf() }
        scheduleSelf({ invalidateSelf() }, 0)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        updateSize()
    }

    private fun updateSize() {
        val res = context.resources
        height = bounds.bottom - padding.bottom - (bounds.top + padding.top)
        width = bounds.right - padding.right - (bounds.left + padding.left)
        intrinsicHeight = res.getDimensionPixelSize(R.dimen.battery_height)
        intrinsicWidth = res.getDimensionPixelSize(R.dimen.battery_height)
        textPaint.textSize = height * 0.7f
    }

    override fun getPadding(padding: Rect): Boolean {
        if (this.padding.left == 0 &&
            this.padding.top == 0 &&
            this.padding.right == 0 &&
            this.padding.bottom == 0
        ) {
            return super.getPadding(padding)
        }
        padding.set(this.padding)
        return true
    }

    private fun getColorForLevel(percent: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < colors.size) {
            thresh = colors[i]
            color = colors[i + 1]
            if (percent <= thresh) {
                // Respect tinting for "normal" level
                return if (i == colors.size - 2) {
                    iconTint
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    private fun batteryColorForLevel(level: Int) =
        if (charging || powerSaveEnabled)
            chargeColor
        else
            getColorForLevel(level)

    fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        val fillColor = if (dualTone) fgColor else singleToneColor

        iconTint = fillColor
        framePaint.color = bgColor
        chargeColor = fillColor

        invalidateSelf()
    }

    override fun draw(c: Canvas) {
        if (batteryLevel == -1) return

        val strokeWidth = powerSavePaint.strokeWidth
        val circleSize = min(width, height)
        var circleRadius = (circleSize / 2f)
        var drawFrac = batteryLevel / 100f

        if (!charging && powerSaveEnabled) {
            circleRadius -= strokeWidth / 2.0f
        }

        framePaint.strokeWidth = 0f
        framePaint.style = Paint.Style.FILL_AND_STROKE
        batteryPaint.strokeWidth = 0f
        batteryPaint.style = Paint.Style.FILL_AND_STROKE
        frame[
                strokeWidth / 2.0f + padding.left, strokeWidth / 2.0f,
                circleSize - strokeWidth / 2.0f + padding.left
        ] = circleSize - strokeWidth / 2.0f
        // set the battery charging color
        batteryPaint.color = batteryColorForLevel(batteryLevel)
        if (chargingAnimator != null) {
            if (batteryLevel == 100) {
                cancelChargingAnimation()
            } else {
                batteryPaint.alpha = batteryAlpha
            }
        }

        if (batteryLevel <= criticalLevel) {
            drawFrac = 0f
        }

        // draw outer circle first
        c.drawCircle(frame.centerX(), frame.centerY(), circleRadius, framePaint)

        c.save()
        // compute percentage text
        if (batteryLevel != 100 && showPercent) {
            val textHeight = -textPaint.fontMetrics.ascent
            val pctText =
                if (batteryLevel > criticalLevel)
                    batteryLevel.toString()
                else
                    warningString
            val pctY = (height + textHeight) * 0.45f
            textPaint.color = batteryColorForLevel(batteryLevel)
            c.drawText(pctText, frame.centerX(), pctY, textPaint)
            var textPath = Path()
            textPaint.getTextPath(pctText, 0, pctText.length, frame.centerX(),
                pctY, textPath)
            c.clipOutPath(textPath)
        }

        // draw colored circle representing charge level
        if (drawFrac != 0f) {
            c.drawCircle(frame.centerX(), frame.centerY(), circleRadius
                * drawFrac, batteryPaint)
        }

        if (!charging && powerSaveEnabled) {
            c.drawCircle(frame.centerX(), frame.centerY(), circleRadius,
                powerSavePaint)
        }
        c.restore()
    }

    // Some stuff required by Drawable.
    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        framePaint.colorFilter = colorFilter
        batteryPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    override fun getOpacity() = PixelFormat.UNKNOWN

    init {
        val res = context.resources
        val color_levels = res.obtainTypedArray(R.array.batterymeter_color_levels)
        val color_values = res.obtainTypedArray(R.array.batterymeter_color_values)
        colors = IntArray(2 * color_levels.length())
        for (i in 0 until color_levels.length()) {
            colors[2 * i] = color_levels.getInt(i, 0)
            if (color_values.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colors[2 * i + 1] = Utils.getColorAttrDefaultColor(
                    context,
                    color_values.getThemeAttributeId(i, 0)
                )
            } else {
                colors[2 * i + 1] = color_values.getColor(i, 0)
            }
        }
        color_levels.recycle()
        color_values.recycle()
        warningString = res.getString(R.string.battery_meter_very_low_overlay_symbol)
        criticalLevel = res.getInteger(
            com.android.internal.R.integer.config_criticalBatteryWarningLevel
        )
        framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        framePaint.color = frameColor
        framePaint.isDither = true
        batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        batteryPaint.isDither = true
        batteryAlpha = batteryPaint.alpha
        textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.strokeWidth = 2f
        textPaint.style = Paint.Style.STROKE
        chargeColor = Utils.getColorStateListDefaultColor(context, R.color.meter_consumed_color)
        powerSavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        powerSavePaint.color = Utils.getColorStateListDefaultColor(
            context,
            R.color.batterymeter_plus_color
        )
        powerSavePaint.style = Paint.Style.STROKE
        powerSavePaint.strokeWidth = 3f
        intrinsicWidth = res.getDimensionPixelSize(R.dimen.battery_width)
        intrinsicHeight = res.getDimensionPixelSize(R.dimen.battery_height)

        dualTone = res.getBoolean(com.android.internal.R.bool.config_batterymeterDualTone)
    }
}
