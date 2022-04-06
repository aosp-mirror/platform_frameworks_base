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
package com.android.keyguard

import android.animation.TimeInterpolator
import android.annotation.ColorInt
import android.annotation.FloatRange
import android.annotation.IntRange
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import java.io.PrintWriter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Displays the time with the hour positioned above the minutes. (ie: 09 above 30 is 9:30)
 * The time's text color is a gradient that changes its colors based on its controller.
 */
class AnimatableClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    private val tag = "AnimatableClockView"

    private var lastMeasureCall: CharSequence = ""

    private val time = Calendar.getInstance()

    private val dozingWeightInternal: Int
    private val lockScreenWeightInternal: Int
    private val isSingleLineInternal: Boolean

    private var format: CharSequence? = null
    private var descFormat: CharSequence? = null

    @ColorInt
    private var dozingColor = 0

    @ColorInt
    private var lockScreenColor = 0

    private var lineSpacingScale = 1f
    private val chargeAnimationDelay: Int
    private var textAnimator: TextAnimator? = null
    private var onTextAnimatorInitialized: Runnable? = null

    val dozingWeight: Int
        get() = if (useBoldedVersion()) dozingWeightInternal + 100 else dozingWeightInternal

    val lockScreenWeight: Int
        get() = if (useBoldedVersion()) lockScreenWeightInternal + 100 else lockScreenWeightInternal

    init {
        val animatableClockViewAttributes = context.obtainStyledAttributes(
            attrs, R.styleable.AnimatableClockView, defStyleAttr, defStyleRes
        )

        try {
            dozingWeightInternal = animatableClockViewAttributes.getInt(
                R.styleable.AnimatableClockView_dozeWeight,
                100
            )
            lockScreenWeightInternal = animatableClockViewAttributes.getInt(
                R.styleable.AnimatableClockView_lockScreenWeight,
                300
            )
            chargeAnimationDelay = animatableClockViewAttributes.getInt(
                R.styleable.AnimatableClockView_chargeAnimationDelay, 200
            )
        } finally {
            animatableClockViewAttributes.recycle()
        }

        val textViewAttributes = context.obtainStyledAttributes(
            attrs, android.R.styleable.TextView,
            defStyleAttr, defStyleRes
        )

        isSingleLineInternal =
            try {
                textViewAttributes.getBoolean(android.R.styleable.TextView_singleLine, false)
            } finally {
                textViewAttributes.recycle()
            }

        refreshFormat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshFormat()
    }

    /**
     * Whether to use a bolded version based on the user specified fontWeightAdjustment.
     */
    fun useBoldedVersion(): Boolean {
        // "Bold text" fontWeightAdjustment is 300.
        return resources.configuration.fontWeightAdjustment > 100
    }

    fun refreshTime() {
        time.timeInMillis = System.currentTimeMillis()
        text = DateFormat.format(format, time)
        contentDescription = DateFormat.format(descFormat, time)
        Log.d(tag, "refreshTime this=$this" +
                " currTimeContextDesc=$contentDescription" +
                " measuredHeight=$measuredHeight" +
                " lastMeasureCall=$lastMeasureCall" +
                " isSingleLineInternal=$isSingleLineInternal")
    }

    fun onTimeZoneChanged(timeZone: TimeZone?) {
        time.timeZone = timeZone
        refreshFormat()
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        lastMeasureCall = DateFormat.format(descFormat, System.currentTimeMillis())
        val animator = textAnimator
        if (animator == null) {
            textAnimator = TextAnimator(layout) { invalidate() }
            onTextAnimatorInitialized?.run()
            onTextAnimatorInitialized = null
        } else {
            animator.updateLayout(layout)
        }
        Log.v(tag, "onMeasure this=$this" +
                " currTimeContextDesc=$contentDescription" +
                " heightMeasureSpecMode=${MeasureSpec.getMode(heightMeasureSpec)}" +
                " heightMeasureSpecSize=${MeasureSpec.getSize(heightMeasureSpec)}" +
                " measuredWidth=$measuredWidth" +
                " measuredHeight=$measuredHeight" +
                " isSingleLineInternal=$isSingleLineInternal")
    }

    override fun onDraw(canvas: Canvas) {
        // intentionally doesn't call super.onDraw here or else the text will be rendered twice
        Log.d(tag, "onDraw this=$this" +
                " currTimeContextDesc=$contentDescription" +
                " isSingleLineInternal=$isSingleLineInternal")
        textAnimator?.draw(canvas)
    }

    fun setLineSpacingScale(scale: Float) {
        lineSpacingScale = scale
        setLineSpacing(0f, lineSpacingScale)
    }

    fun setColors(@ColorInt dozingColor: Int, lockScreenColor: Int) {
        this.dozingColor = dozingColor
        this.lockScreenColor = lockScreenColor
    }

    fun animateAppearOnLockscreen() {
        if (textAnimator == null) {
            return
        }
        setTextStyle(
            weight = dozingWeight,
            textSize = -1f,
            color = lockScreenColor,
            animate = false,
            duration = 0,
            delay = 0,
            onAnimationEnd = null
        )
        setTextStyle(
            weight = lockScreenWeight,
            textSize = -1f,
            color = lockScreenColor,
            animate = true,
            duration = APPEAR_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null
        )
    }

    fun animateFoldAppear() {
        if (textAnimator == null) {
            return
        }
        setTextStyle(
            weight = lockScreenWeightInternal,
            textSize = -1f,
            color = lockScreenColor,
            animate = false,
            duration = 0,
            delay = 0,
            onAnimationEnd = null
        )
        setTextStyle(
            weight = dozingWeightInternal,
            textSize = -1f,
            color = dozingColor,
            animate = true,
            interpolator = Interpolators.EMPHASIZED_DECELERATE,
            duration = StackStateAnimator.ANIMATION_DURATION_FOLD_TO_AOD.toLong(),
            delay = 0,
            onAnimationEnd = null
        )
    }

    fun animateCharge(dozeStateGetter: DozeStateGetter) {
        if (textAnimator == null || textAnimator!!.isRunning()) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        val startAnimPhase2 = Runnable {
            setTextStyle(
                weight = if (dozeStateGetter.isDozing) dozingWeight else lockScreenWeight,
                textSize = -1f,
                color = null,
                animate = true,
                duration = CHARGE_ANIM_DURATION_PHASE_1,
                delay = 0,
                onAnimationEnd = null
            )
        }
        setTextStyle(
            weight = if (dozeStateGetter.isDozing) lockScreenWeight else dozingWeight,
            textSize = -1f,
            color = null,
            animate = true,
            duration = CHARGE_ANIM_DURATION_PHASE_0,
            delay = chargeAnimationDelay.toLong(),
            onAnimationEnd = startAnimPhase2
        )
    }

    fun animateDoze(isDozing: Boolean, animate: Boolean) {
        setTextStyle(
            weight = if (isDozing) dozingWeight else lockScreenWeight,
            textSize = -1f,
            color = if (isDozing) dozingColor else lockScreenColor,
            animate = animate,
            duration = DOZE_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null
        )
    }

    /**
     * Set text style with an optional animation.
     *
     * By passing -1 to weight, the view preserves its current weight.
     * By passing -1 to textSize, the view preserves its current text size.
     *
     * @param weight text weight.
     * @param textSize font size.
     * @param animate true to animate the text style change, otherwise false.
     */
    private fun setTextStyle(
        @IntRange(from = 0, to = 1000) weight: Int,
        @FloatRange(from = 0.0) textSize: Float,
        color: Int?,
        animate: Boolean,
        interpolator: TimeInterpolator?,
        duration: Long,
        delay: Long,
        onAnimationEnd: Runnable?
    ) {
        if (textAnimator != null) {
            textAnimator?.setTextStyle(
                weight = weight,
                textSize = textSize,
                color = color,
                animate = animate,
                duration = duration,
                interpolator = interpolator,
                delay = delay,
                onAnimationEnd = onAnimationEnd
            )
        } else {
            // when the text animator is set, update its start values
            onTextAnimatorInitialized = Runnable {
                textAnimator?.setTextStyle(
                    weight = weight,
                    textSize = textSize,
                    color = color,
                    animate = false,
                    duration = duration,
                    interpolator = interpolator,
                    delay = delay,
                    onAnimationEnd = onAnimationEnd
                )
            }
        }
    }

    private fun setTextStyle(
        @IntRange(from = 0, to = 1000) weight: Int,
        @FloatRange(from = 0.0) textSize: Float,
        color: Int?,
        animate: Boolean,
        duration: Long,
        delay: Long,
        onAnimationEnd: Runnable?
    ) {
        setTextStyle(
            weight = weight,
            textSize = textSize,
            color = color,
            animate = animate,
            interpolator = null,
            duration = duration,
            delay = delay,
            onAnimationEnd = onAnimationEnd
        )
    }

    fun refreshFormat() {
        Patterns.update(context)
        val use24HourFormat = DateFormat.is24HourFormat(context)

        format = when {
            isSingleLineInternal && use24HourFormat -> Patterns.sClockView24
            !isSingleLineInternal && use24HourFormat -> DOUBLE_LINE_FORMAT_24_HOUR
            isSingleLineInternal && !use24HourFormat -> Patterns.sClockView12
            else -> DOUBLE_LINE_FORMAT_12_HOUR
        }

        descFormat = if (use24HourFormat) Patterns.sClockView24 else Patterns.sClockView12

        refreshTime()
    }

    fun dump(pw: PrintWriter) {
        pw.println("$this")
        pw.println("    measuredWidth=$measuredWidth")
        pw.println("    measuredHeight=$measuredHeight")
        pw.println("    singleLineInternal=$isSingleLineInternal")
        pw.println("    lastMeasureCall=$lastMeasureCall")
        pw.println("    currText=$text")
        pw.println("    currTimeContextDesc=$contentDescription")
        pw.println("    time=$time")
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private object Patterns {
        var sClockView12: String? = null
        var sClockView24: String? = null
        var sCacheKey: String? = null

        fun update(context: Context) {
            val locale = Locale.getDefault()
            val res = context.resources
            val clockView12Skel = res.getString(R.string.clock_12hr_format)
            val clockView24Skel = res.getString(R.string.clock_24hr_format)
            val key = locale.toString() + clockView12Skel + clockView24Skel
            if (key == sCacheKey) return

            val clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel)
            sClockView12 = clockView12

            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                sClockView12 = clockView12.replace("a".toRegex(), "").trim { it <= ' ' }
            }
            sClockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel)
            sCacheKey = key
        }
    }

    interface DozeStateGetter {
        val isDozing: Boolean
    }
}

private const val DOUBLE_LINE_FORMAT_12_HOUR = "hh\nmm"
private const val DOUBLE_LINE_FORMAT_24_HOUR = "HH\nmm"
private const val DOZE_ANIM_DURATION: Long = 300
private const val APPEAR_ANIM_DURATION: Long = 350
private const val CHARGE_ANIM_DURATION_PHASE_0: Long = 500
private const val CHARGE_ANIM_DURATION_PHASE_1: Long = 1000
