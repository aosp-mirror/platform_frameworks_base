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
package com.android.systemui.shared.clocks

import android.animation.TimeInterpolator
import android.annotation.ColorInt
import android.annotation.FloatRange
import android.annotation.IntRange
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.text.Layout
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.MathUtils
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.animation.GlyphCallback
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.TextAnimator
import com.android.systemui.customization.R
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import java.io.PrintWriter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Displays the time with the hour positioned above the minutes. (ie: 09 above 30 is 9:30)
 * The time's text color is a gradient that changes its colors based on its controller.
 */
@SuppressLint("AppCompatCustomView")
class AnimatableClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    var logBuffer: LogBuffer? = null

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

    @VisibleForTesting var textAnimatorFactory: (Layout, () -> Unit) -> TextAnimator =
        { layout, invalidateCb -> TextAnimator(layout, invalidateCb) }
    @VisibleForTesting var isAnimationEnabled: Boolean = true
    @VisibleForTesting var timeOverrideInMillis: Long? = null

    val dozingWeight: Int
        get() = if (useBoldedVersion()) dozingWeightInternal + 100 else dozingWeightInternal

    val lockScreenWeight: Int
        get() = if (useBoldedVersion()) lockScreenWeightInternal + 100 else lockScreenWeightInternal

    /**
     * The number of pixels below the baseline. For fonts that support languages such as
     * Burmese, this space can be significant and should be accounted for when computing layout.
     */
    val bottom get() = paint?.fontMetrics?.bottom ?: 0f

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
        logBuffer?.log(TAG, DEBUG, "onAttachedToWindow")
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
        time.timeInMillis = timeOverrideInMillis ?: System.currentTimeMillis()
        contentDescription = DateFormat.format(descFormat, time)
        val formattedText = DateFormat.format(format, time)
        logBuffer?.log(TAG, DEBUG,
                { str1 = formattedText?.toString() },
                { "refreshTime: new formattedText=$str1" }
        )
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (!TextUtils.equals(text, formattedText)) {
            text = formattedText
            logBuffer?.log(TAG, DEBUG,
                    { str1 = formattedText?.toString() },
                    { "refreshTime: done setting new time text to: $str1" }
            )
            // Because the TextLayout may mutate under the hood as a result of the new text, we
            // notify the TextAnimator that it may have changed and request a measure/layout. A
            // crash will occur on the next invocation of setTextStyle if the layout is mutated
            // without being notified TextInterpolator being notified.
            if (layout != null) {
                textAnimator?.updateLayout(layout)
                logBuffer?.log(TAG, DEBUG, "refreshTime: done updating textAnimator layout")
            }
            requestLayout()
            logBuffer?.log(TAG, DEBUG, "refreshTime: after requestLayout")
        }
    }

    fun onTimeZoneChanged(timeZone: TimeZone?) {
        time.timeZone = timeZone
        refreshFormat()
        logBuffer?.log(TAG, DEBUG,
                { str1 = timeZone?.toString() },
                { "onTimeZoneChanged newTimeZone=$str1" }
        )
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val animator = textAnimator
        if (animator == null) {
            textAnimator = textAnimatorFactory(layout, ::invalidate)
            onTextAnimatorInitialized?.run()
            onTextAnimatorInitialized = null
        } else {
            animator.updateLayout(layout)
        }
        logBuffer?.log(TAG, DEBUG, "onMeasure")
    }

    override fun onDraw(canvas: Canvas) {
        // Use textAnimator to render text if animation is enabled.
        // Otherwise default to using standard draw functions.
        if (isAnimationEnabled) {
            // intentionally doesn't call super.onDraw here or else the text will be rendered twice
            textAnimator?.draw(canvas)
        } else {
            super.onDraw(canvas)
        }
        logBuffer?.log(TAG, DEBUG, "onDraw")
    }

    override fun invalidate() {
        super.invalidate()
        logBuffer?.log(TAG, DEBUG, "invalidate")
    }

    override fun onTextChanged(
            text: CharSequence,
            start: Int,
            lengthBefore: Int,
            lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        logBuffer?.log(TAG, DEBUG,
                { str1 = text.toString() },
                { "onTextChanged text=$str1" }
        )
    }

    fun setLineSpacingScale(scale: Float) {
        lineSpacingScale = scale
        setLineSpacing(0f, lineSpacingScale)
    }

    fun setColors(@ColorInt dozingColor: Int, lockScreenColor: Int) {
        this.dozingColor = dozingColor
        this.lockScreenColor = lockScreenColor
    }

    fun animateColorChange() {
        logBuffer?.log(TAG, DEBUG, "animateColorChange")
        setTextStyle(
            weight = lockScreenWeight,
            textSize = -1f,
            color = null, /* using current color */
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
            duration = COLOR_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null
        )
    }

    fun animateAppearOnLockscreen() {
        logBuffer?.log(TAG, DEBUG, "animateAppearOnLockscreen")
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
            animate = isAnimationEnabled,
            duration = APPEAR_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null
        )
    }

    fun animateFoldAppear(animate: Boolean = true) {
        if (isAnimationEnabled && textAnimator == null) {
            return
        }
        logBuffer?.log(TAG, DEBUG, "animateFoldAppear")
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
            animate = animate && isAnimationEnabled,
            interpolator = Interpolators.EMPHASIZED_DECELERATE,
            duration = ANIMATION_DURATION_FOLD_TO_AOD.toLong(),
            delay = 0,
            onAnimationEnd = null
        )
    }

    fun animateCharge(isDozing: () -> Boolean) {
        if (textAnimator == null || textAnimator!!.isRunning()) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        logBuffer?.log(TAG, DEBUG, "animateCharge")
        val startAnimPhase2 = Runnable {
            setTextStyle(
                weight = if (isDozing()) dozingWeight else lockScreenWeight,
                textSize = -1f,
                color = null,
                animate = isAnimationEnabled,
                duration = CHARGE_ANIM_DURATION_PHASE_1,
                delay = 0,
                onAnimationEnd = null
            )
        }
        setTextStyle(
            weight = if (isDozing()) lockScreenWeight else dozingWeight,
            textSize = -1f,
            color = null,
            animate = isAnimationEnabled,
            duration = CHARGE_ANIM_DURATION_PHASE_0,
            delay = chargeAnimationDelay.toLong(),
            onAnimationEnd = startAnimPhase2
        )
    }

    fun animateDoze(isDozing: Boolean, animate: Boolean) {
        logBuffer?.log(TAG, DEBUG, "animateDoze")
        setTextStyle(
            weight = if (isDozing) dozingWeight else lockScreenWeight,
            textSize = -1f,
            color = if (isDozing) dozingColor else lockScreenColor,
            animate = animate && isAnimationEnabled,
            duration = DOZE_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null
        )
    }

    // The offset of each glyph from where it should be.
    private var glyphOffsets = mutableListOf(0.0f, 0.0f, 0.0f, 0.0f)

    private var lastSeenAnimationProgress = 1.0f

    // If the animation is being reversed, the target offset for each glyph for the "stop".
    private var animationCancelStartPosition = mutableListOf(0.0f, 0.0f, 0.0f, 0.0f)
    private var animationCancelStopPosition = 0.0f

    // Whether the currently playing animation needed a stop (and thus, is shortened).
    private var currentAnimationNeededStop = false

    private val glyphFilter: GlyphCallback = { positionedGlyph, _ ->
        val offset = positionedGlyph.lineNo * DIGITS_PER_LINE + positionedGlyph.glyphIndex
        if (offset < glyphOffsets.size) {
            positionedGlyph.x += glyphOffsets[offset]
        }
    }

    /**
     * Set text style with an optional animation.
     *
     * By passing -1 to weight, the view preserves its current weight.
     * By passing -1 to textSize, the view preserves its current text size.
     * By passing null to color, the view preserves its current color.
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
                animate = animate && isAnimationEnabled,
                duration = duration,
                interpolator = interpolator,
                delay = delay,
                onAnimationEnd = onAnimationEnd
            )
            textAnimator?.glyphFilter = glyphFilter
            if (color != null && !isAnimationEnabled) {
                setTextColor(color)
            }
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
                textAnimator?.glyphFilter = glyphFilter
                if (color != null && !isAnimationEnabled) {
                    setTextColor(color)
                }
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
            animate = animate && isAnimationEnabled,
            interpolator = null,
            duration = duration,
            delay = delay,
            onAnimationEnd = onAnimationEnd
        )
    }

    fun refreshFormat() = refreshFormat(DateFormat.is24HourFormat(context))
    fun refreshFormat(use24HourFormat: Boolean) {
        Patterns.update(context)

        format = when {
            isSingleLineInternal && use24HourFormat -> Patterns.sClockView24
            !isSingleLineInternal && use24HourFormat -> DOUBLE_LINE_FORMAT_24_HOUR
            isSingleLineInternal && !use24HourFormat -> Patterns.sClockView12
            else -> DOUBLE_LINE_FORMAT_12_HOUR
        }
        logBuffer?.log(TAG, DEBUG,
                { str1 = format?.toString() },
                { "refreshFormat format=$str1" }
        )

        descFormat = if (use24HourFormat) Patterns.sClockView24 else Patterns.sClockView12
        refreshTime()
    }

    fun dump(pw: PrintWriter) {
        pw.println("$this")
        pw.println("    alpha=$alpha")
        pw.println("    measuredWidth=$measuredWidth")
        pw.println("    measuredHeight=$measuredHeight")
        pw.println("    singleLineInternal=$isSingleLineInternal")
        pw.println("    currText=$text")
        pw.println("    currTimeContextDesc=$contentDescription")
        pw.println("    dozingWeightInternal=$dozingWeightInternal")
        pw.println("    lockScreenWeightInternal=$lockScreenWeightInternal")
        pw.println("    dozingColor=$dozingColor")
        pw.println("    lockScreenColor=$lockScreenColor")
        pw.println("    time=$time")
    }

    fun moveForSplitShade(fromRect: Rect, toRect: Rect, fraction: Float) {
        // Do we need to cancel an in-flight animation?
        // Need to also check against 0.0f here; we can sometimes get two calls with fraction == 0,
        // which trips up the check otherwise.
        if (lastSeenAnimationProgress != 1.0f &&
                lastSeenAnimationProgress != 0.0f &&
                fraction == 0.0f) {
            // New animation, but need to stop the old one. Figure out where each glyph currently
            // is in relation to the box position. After that, use the leading digit's current
            // position as the stop target.
            currentAnimationNeededStop = true

            // We assume that the current glyph offsets would be relative to the "from" position.
            val moveAmount = toRect.left - fromRect.left

            // Remap the current glyph offsets to be relative to the new "end" position, and figure
            // out the start/end positions for the stop animation.
            for (i in 0 until NUM_DIGITS) {
                glyphOffsets[i] = -moveAmount + glyphOffsets[i]
                animationCancelStartPosition[i] = glyphOffsets[i]
            }

            // Use the leading digit's offset as the stop position.
            if (toRect.left > fromRect.left) {
                // It _was_ moving left
                animationCancelStopPosition = glyphOffsets[0]
            } else {
                // It was moving right
                animationCancelStopPosition = glyphOffsets[1]
            }
        }

        // Is there a cancellation in progress?
        if (currentAnimationNeededStop && fraction < ANIMATION_CANCELLATION_TIME) {
            val animationStopProgress = MathUtils.constrainedMap(
                    0.0f, 1.0f, 0.0f, ANIMATION_CANCELLATION_TIME, fraction
            )

            // One of the digits has already stopped.
            val animationStopStep = 1.0f / (NUM_DIGITS - 1)

            for (i in 0 until NUM_DIGITS) {
                val stopAmount = if (toRect.left > fromRect.left) {
                    // It was moving left (before flipping)
                    MOVE_LEFT_DELAYS[i] * animationStopStep
                } else {
                    // It was moving right (before flipping)
                    MOVE_RIGHT_DELAYS[i] * animationStopStep
                }

                // Leading digit stops immediately.
                if (stopAmount == 0.0f) {
                    glyphOffsets[i] = animationCancelStopPosition
                } else {
                    val actualStopAmount = MathUtils.constrainedMap(
                            0.0f, 1.0f, 0.0f, stopAmount, animationStopProgress
                    )
                    val easedProgress = MOVE_INTERPOLATOR.getInterpolation(actualStopAmount)
                    val glyphMoveAmount =
                            animationCancelStopPosition - animationCancelStartPosition[i]
                    glyphOffsets[i] =
                            animationCancelStartPosition[i] + glyphMoveAmount * easedProgress
                }
            }
        } else {
            // Normal part of the animation.
            // Do we need to remap the animation progress to take account of the cancellation?
            val actualFraction = if (currentAnimationNeededStop) {
                MathUtils.constrainedMap(
                        0.0f, 1.0f, ANIMATION_CANCELLATION_TIME, 1.0f, fraction
                )
            } else {
                fraction
            }

            val digitFractions = (0 until NUM_DIGITS).map {
                // The delay for each digit, in terms of fraction (i.e. the digit should not move
                // during 0.0 - 0.1).
                val initialDelay = if (toRect.left > fromRect.left) {
                    MOVE_RIGHT_DELAYS[it] * MOVE_DIGIT_STEP
                } else {
                    MOVE_LEFT_DELAYS[it] * MOVE_DIGIT_STEP
                }

                val f = MathUtils.constrainedMap(
                        0.0f, 1.0f,
                        initialDelay, initialDelay + AVAILABLE_ANIMATION_TIME,
                        actualFraction
                )
                MOVE_INTERPOLATOR.getInterpolation(max(min(f, 1.0f), 0.0f))
            }

            // Was there an animation halt?
            val moveAmount = if (currentAnimationNeededStop) {
                // Only need to animate over the remaining space if the animation was aborted.
                -animationCancelStopPosition
            } else {
                toRect.left.toFloat() - fromRect.left.toFloat()
            }

            for (i in 0 until NUM_DIGITS) {
                glyphOffsets[i] = -moveAmount + (moveAmount * digitFractions[i])
            }
        }

        invalidate()

        if (fraction == 1.0f) {
            // Reset
            currentAnimationNeededStop = false
        }

        lastSeenAnimationProgress = fraction

        // Ensure that the actual clock container is always in the "end" position.
        this.setLeftTopRightBottom(toRect.left, toRect.top, toRect.right, toRect.bottom)
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

    companion object {
        private val TAG = AnimatableClockView::class.simpleName!!
        const val ANIMATION_DURATION_FOLD_TO_AOD: Int = 600
        private const val DOUBLE_LINE_FORMAT_12_HOUR = "hh\nmm"
        private const val DOUBLE_LINE_FORMAT_24_HOUR = "HH\nmm"
        private const val DOZE_ANIM_DURATION: Long = 300
        private const val APPEAR_ANIM_DURATION: Long = 350
        private const val CHARGE_ANIM_DURATION_PHASE_0: Long = 500
        private const val CHARGE_ANIM_DURATION_PHASE_1: Long = 1000
        private const val COLOR_ANIM_DURATION: Long = 400

        // Constants for the animation
        private val MOVE_INTERPOLATOR = Interpolators.EMPHASIZED

        // Calculate the positions of all of the digits...
        // Offset each digit by, say, 0.1
        // This means that each digit needs to move over a slice of "fractions", i.e. digit 0 should
        // move from 0.0 - 0.7, digit 1 from 0.1 - 0.8, digit 2 from 0.2 - 0.9, and digit 3
        // from 0.3 - 1.0.
        private const val NUM_DIGITS = 4
        private const val DIGITS_PER_LINE = 2

        // How much of "fraction" to spend on canceling the animation, if needed
        private const val ANIMATION_CANCELLATION_TIME = 0.4f

        // Delays. Each digit's animation should have a slight delay, so we get a nice
        // "stepping" effect. When moving right, the second digit of the hour should move first.
        // When moving left, the first digit of the hour should move first. The lists encode
        // the delay for each digit (hour[0], hour[1], minute[0], minute[1]), to be multiplied
        // by delayMultiplier.
        private val MOVE_LEFT_DELAYS = listOf(0, 1, 2, 3)
        private val MOVE_RIGHT_DELAYS = listOf(1, 0, 3, 2)

        // How much delay to apply to each subsequent digit. This is measured in terms of "fraction"
        // (i.e. a value of 0.1 would cause a digit to wait until fraction had hit 0.1, or 0.2 etc
        // before moving).
        //
        // The current specs dictate that each digit should have a 33ms gap between them. The
        // overall time is 1s right now.
        private const val MOVE_DIGIT_STEP = 0.033f

        // Total available transition time for each digit, taking into account the step. If step is
        // 0.1, then digit 0 would animate over 0.0 - 0.7, making availableTime 0.7.
        private val AVAILABLE_ANIMATION_TIME = 1.0f - MOVE_DIGIT_STEP * (NUM_DIGITS - 1)
    }
}
