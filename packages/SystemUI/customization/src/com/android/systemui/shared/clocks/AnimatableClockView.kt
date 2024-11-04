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
import android.annotation.IntRange
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.MathUtils.constrainedMap
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.View
import android.view.View.MeasureSpec.EXACTLY
import android.widget.TextView
import com.android.app.animation.Interpolators
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.animation.GlyphCallback
import com.android.systemui.animation.TextAnimator
import com.android.systemui.customization.R
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogcatOnlyMessageBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import java.io.PrintWriter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/**
 * Displays the time with the hour positioned above the minutes (ie: 09 above 30 is 9:30). The
 * time's text color is a gradient that changes its colors based on its controller.
 */
@SuppressLint("AppCompatCustomView")
class AnimatableClockView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    // To protect us from issues from this being null while the TextView constructor is running, we
    // implement the get method and ensure a value is returned before initialization is complete.
    private var logger = DEFAULT_LOGGER
        get() = field ?: DEFAULT_LOGGER

    var messageBuffer: MessageBuffer
        get() = logger.buffer
        set(value) {
            logger = Logger(value, TAG)
        }

    var hasCustomPositionUpdatedAnimation: Boolean = false
    var migratedClocks: Boolean = false

    private val time = Calendar.getInstance()

    private val dozingWeightInternal: Int
    private val lockScreenWeightInternal: Int
    private val isSingleLineInternal: Boolean

    private var format: CharSequence? = null
    private var descFormat: CharSequence? = null

    @ColorInt private var dozingColor = 0
    @ColorInt private var lockScreenColor = 0

    private var lineSpacingScale = 1f
    private val chargeAnimationDelay: Int
    private var textAnimator: TextAnimator? = null
    private var onTextAnimatorInitialized: ((TextAnimator) -> Unit)? = null

    private var translateForCenterAnimation = false
    private val parentWidth: Int
        get() = (parent as View).measuredWidth

    // last text size which is not constrained by view height
    private var lastUnconstrainedTextSize: Float = Float.MAX_VALUE

    @VisibleForTesting
    var textAnimatorFactory: (Layout, () -> Unit) -> TextAnimator = { layout, invalidateCb ->
        TextAnimator(layout, NUM_CLOCK_FONT_ANIMATION_STEPS, invalidateCb)
    }

    // Used by screenshot tests to provide stability
    @VisibleForTesting var isAnimationEnabled: Boolean = true
    @VisibleForTesting var timeOverrideInMillis: Long? = null

    val dozingWeight: Int
        get() = if (useBoldedVersion()) dozingWeightInternal + 100 else dozingWeightInternal

    val lockScreenWeight: Int
        get() = if (useBoldedVersion()) lockScreenWeightInternal + 100 else lockScreenWeightInternal

    /**
     * The number of pixels below the baseline. For fonts that support languages such as Burmese,
     * this space can be significant and should be accounted for when computing layout.
     */
    val bottom: Float
        get() = paint?.fontMetrics?.bottom ?: 0f

    init {
        val animatableClockViewAttributes =
            context.obtainStyledAttributes(
                attrs,
                R.styleable.AnimatableClockView,
                defStyleAttr,
                defStyleRes,
            )

        try {
            dozingWeightInternal =
                animatableClockViewAttributes.getInt(
                    R.styleable.AnimatableClockView_dozeWeight,
                    /* default = */ 100,
                )
            lockScreenWeightInternal =
                animatableClockViewAttributes.getInt(
                    R.styleable.AnimatableClockView_lockScreenWeight,
                    /* default = */ 300,
                )
            chargeAnimationDelay =
                animatableClockViewAttributes.getInt(
                    R.styleable.AnimatableClockView_chargeAnimationDelay,
                    /* default = */ 200,
                )
        } finally {
            animatableClockViewAttributes.recycle()
        }

        val textViewAttributes =
            context.obtainStyledAttributes(
                attrs,
                android.R.styleable.TextView,
                defStyleAttr,
                defStyleRes,
            )

        try {
            isSingleLineInternal =
                textViewAttributes.getBoolean(
                    android.R.styleable.TextView_singleLine,
                    /* default = */ false,
                )
        } finally {
            textViewAttributes.recycle()
        }

        refreshFormat()
    }

    override fun onAttachedToWindow() {
        logger.d("onAttachedToWindow")
        super.onAttachedToWindow()
        refreshFormat()
    }

    /** Whether to use a bolded version based on the user specified fontWeightAdjustment. */
    fun useBoldedVersion(): Boolean {
        // "Bold text" fontWeightAdjustment is 300.
        return resources.configuration.fontWeightAdjustment > 100
    }

    fun refreshTime() {
        time.timeInMillis = timeOverrideInMillis ?: System.currentTimeMillis()
        contentDescription = DateFormat.format(descFormat, time)
        val formattedText = DateFormat.format(format, time)
        logger.d({ "refreshTime: new formattedText=$str1" }) { str1 = formattedText?.toString() }

        // Setting text actually triggers a layout pass in TextView (because the text view is set to
        // wrap_content width and TextView always relayouts for this). This avoids needless relayout
        // if the text didn't actually change.
        if (TextUtils.equals(text, formattedText)) {
            return
        }

        text = formattedText
        logger.d({ "refreshTime: done setting new time text to: $str1" }) {
            str1 = formattedText?.toString()
        }

        // Because the TextLayout may mutate under the hood as a result of the new text, we notify
        // the TextAnimator that it may have changed and request a measure/layout. A crash will
        // occur on the next invocation of setTextStyle if the layout is mutated without being
        // notified TextInterpolator being notified.
        if (layout != null) {
            textAnimator?.updateLayout(layout)
            logger.d("refreshTime: done updating textAnimator layout")
        }

        requestLayout()
        logger.d("refreshTime: after requestLayout")
    }

    fun onTimeZoneChanged(timeZone: TimeZone?) {
        logger.d({ "onTimeZoneChanged($str1)" }) { str1 = timeZone?.toString() }
        time.timeZone = timeZone
        refreshFormat()
    }

    override fun setTextSize(type: Int, size: Float) {
        super.setTextSize(type, size)
        lastUnconstrainedTextSize = if (type == COMPLEX_UNIT_PX) size else Float.MAX_VALUE
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.d("onMeasure")

        if (
            migratedClocks &&
                !isSingleLineInternal &&
                MeasureSpec.getMode(heightMeasureSpec) == EXACTLY
        ) {
            // Call straight into TextView.setTextSize to avoid setting lastUnconstrainedTextSize
            val size = min(lastUnconstrainedTextSize, MeasureSpec.getSize(heightMeasureSpec) / 2F)
            super.setTextSize(COMPLEX_UNIT_PX, size)
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        textAnimator?.let { animator -> animator.updateLayout(layout, textSize) }
            ?: run {
                textAnimator =
                    textAnimatorFactory(layout, ::invalidate).also {
                        onTextAnimatorInitialized?.invoke(it)
                        onTextAnimatorInitialized = null
                    }
            }

        if (migratedClocks && hasCustomPositionUpdatedAnimation) {
            // Expand width to avoid clock being clipped during stepping animation
            val targetWidth = measuredWidth + MeasureSpec.getSize(widthMeasureSpec) / 2

            // This comparison is effectively a check if we're in splitshade or not
            translateForCenterAnimation = parentWidth > targetWidth
            if (translateForCenterAnimation) {
                setMeasuredDimension(targetWidth, measuredHeight)
            }
        } else {
            translateForCenterAnimation = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        if (translateForCenterAnimation) {
            canvas.translate(parentWidth / 4f, 0f)
        }

        logger.d({ "onDraw($str1)" }) { str1 = text.toString() }
        // intentionally doesn't call super.onDraw here or else the text will be rendered twice
        textAnimator?.draw(canvas)
        canvas.restore()
    }

    override fun invalidate() {
        logger.d("invalidate")
        super.invalidate()
    }

    override fun onTextChanged(
        text: CharSequence,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        logger.d({ "onTextChanged($str1)" }) { str1 = text.toString() }
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
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
        logger.d("animateColorChange")
        setTextStyle(
            weight = lockScreenWeight,
            color = null, /* using current color */
            animate = false,
            interpolator = null,
            duration = 0,
            delay = 0,
            onAnimationEnd = null,
        )
        setTextStyle(
            weight = lockScreenWeight,
            color = lockScreenColor,
            animate = true,
            interpolator = null,
            duration = COLOR_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null,
        )
    }

    fun animateAppearOnLockscreen() {
        logger.d("animateAppearOnLockscreen")
        setTextStyle(
            weight = dozingWeight,
            color = lockScreenColor,
            animate = false,
            interpolator = null,
            duration = 0,
            delay = 0,
            onAnimationEnd = null,
        )
        setTextStyle(
            weight = lockScreenWeight,
            color = lockScreenColor,
            animate = true,
            duration = APPEAR_ANIM_DURATION,
            interpolator = Interpolators.EMPHASIZED_DECELERATE,
            delay = 0,
            onAnimationEnd = null,
        )
    }

    fun animateFoldAppear(animate: Boolean = true) {
        if (textAnimator == null) {
            return
        }

        logger.d("animateFoldAppear")
        setTextStyle(
            weight = lockScreenWeightInternal,
            color = lockScreenColor,
            animate = false,
            interpolator = null,
            duration = 0,
            delay = 0,
            onAnimationEnd = null,
        )
        setTextStyle(
            weight = dozingWeightInternal,
            color = dozingColor,
            animate = animate,
            interpolator = Interpolators.EMPHASIZED_DECELERATE,
            duration = ANIMATION_DURATION_FOLD_TO_AOD.toLong(),
            delay = 0,
            onAnimationEnd = null,
        )
    }

    fun animateCharge(isDozing: () -> Boolean) {
        // Skip charge animation if dozing animation is already playing.
        if (textAnimator == null || textAnimator!!.isRunning()) {
            return
        }

        logger.d("animateCharge")
        val startAnimPhase2 = Runnable {
            setTextStyle(
                weight = if (isDozing()) dozingWeight else lockScreenWeight,
                color = null,
                animate = true,
                interpolator = null,
                duration = CHARGE_ANIM_DURATION_PHASE_1,
                delay = 0,
                onAnimationEnd = null,
            )
        }
        setTextStyle(
            weight = if (isDozing()) lockScreenWeight else dozingWeight,
            color = null,
            animate = true,
            interpolator = null,
            duration = CHARGE_ANIM_DURATION_PHASE_0,
            delay = chargeAnimationDelay.toLong(),
            onAnimationEnd = startAnimPhase2,
        )
    }

    fun animateDoze(isDozing: Boolean, animate: Boolean) {
        logger.d("animateDoze")
        setTextStyle(
            weight = if (isDozing) dozingWeight else lockScreenWeight,
            color = if (isDozing) dozingColor else lockScreenColor,
            animate = animate,
            interpolator = null,
            duration = DOZE_ANIM_DURATION,
            delay = 0,
            onAnimationEnd = null,
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
     * - By passing -1 to weight, the view preserves its current weight.
     * - By passing -1 to textSize, the view preserves its current text size.
     * - By passing null to color, the view preserves its current color.
     *
     * @param weight text weight.
     * @param textSize font size.
     * @param animate true to animate the text style change, otherwise false.
     */
    private fun setTextStyle(
        @IntRange(from = 0, to = 1000) weight: Int,
        color: Int?,
        animate: Boolean,
        interpolator: TimeInterpolator?,
        duration: Long,
        delay: Long,
        onAnimationEnd: Runnable?,
    ) {
        textAnimator?.let {
            it.setTextStyle(
                weight = weight,
                color = color,
                animate = animate && isAnimationEnabled,
                duration = duration,
                interpolator = interpolator,
                delay = delay,
                onAnimationEnd = onAnimationEnd,
            )
            it.glyphFilter = glyphFilter
        }
            ?: run {
                // when the text animator is set, update its start values
                onTextAnimatorInitialized = { textAnimator ->
                    textAnimator.setTextStyle(
                        weight = weight,
                        color = color,
                        animate = false,
                        duration = duration,
                        interpolator = interpolator,
                        delay = delay,
                        onAnimationEnd = onAnimationEnd,
                    )
                    textAnimator.glyphFilter = glyphFilter
                }
            }
    }

    fun refreshFormat() = refreshFormat(DateFormat.is24HourFormat(context))

    fun refreshFormat(use24HourFormat: Boolean) {
        Patterns.update(context)

        format =
            when {
                isSingleLineInternal && use24HourFormat -> Patterns.sClockView24
                !isSingleLineInternal && use24HourFormat -> DOUBLE_LINE_FORMAT_24_HOUR
                isSingleLineInternal && !use24HourFormat -> Patterns.sClockView12
                else -> DOUBLE_LINE_FORMAT_12_HOUR
            }
        logger.d({ "refreshFormat($str1)" }) { str1 = format?.toString() }

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

    private val moveToCenterDelays: List<Int>
        get() = if (isLayoutRtl) MOVE_LEFT_DELAYS else MOVE_RIGHT_DELAYS

    private val moveToSideDelays: List<Int>
        get() = if (isLayoutRtl) MOVE_RIGHT_DELAYS else MOVE_LEFT_DELAYS

    /**
     * Offsets the glyphs of the clock for the step clock animation.
     *
     * The animation makes the glyphs of the clock move at different speeds, when the clock is
     * moving horizontally.
     *
     * @param clockStartLeft the [getLeft] position of the clock, before it started moving.
     * @param clockMoveDirection the direction in which it is moving. A positive number means right,
     *   and negative means left.
     * @param moveFraction fraction of the clock movement. 0 means it is at the beginning, and 1
     *   means it finished moving.
     */
    fun offsetGlyphsForStepClockAnimation(
        clockStartLeft: Int,
        clockMoveDirection: Int,
        moveFraction: Float,
    ) {
        val isMovingToCenter = if (isLayoutRtl) clockMoveDirection < 0 else clockMoveDirection > 0
        // The sign of moveAmountDeltaForDigit is already set here
        // we can interpret (left - clockStartLeft) as (destinationPosition - originPosition)
        // so we no longer need to multiply direct sign to moveAmountDeltaForDigit
        val currentMoveAmount = left - clockStartLeft
        for (i in 0 until NUM_DIGITS) {
            val digitFraction =
                getDigitFraction(
                    digit = i,
                    isMovingToCenter = isMovingToCenter,
                    fraction = moveFraction,
                )
            val moveAmountForDigit = currentMoveAmount * digitFraction
            val moveAmountDeltaForDigit = moveAmountForDigit - currentMoveAmount
            glyphOffsets[i] = moveAmountDeltaForDigit
        }
        invalidate()
    }

    /**
     * Offsets the glyphs of the clock for the step clock animation.
     *
     * The animation makes the glyphs of the clock move at different speeds, when the clock is
     * moving horizontally. This method uses direction, distance, and fraction to determine offset.
     *
     * @param distance is the total distance in pixels to offset the glyphs when animation
     *   completes. Negative distance means we are animating the position towards the center.
     * @param fraction fraction of the clock movement. 0 means it is at the beginning, and 1 means
     *   it finished moving.
     */
    fun offsetGlyphsForStepClockAnimation(distance: Float, fraction: Float) {
        for (i in 0 until NUM_DIGITS) {
            val dir = if (isLayoutRtl) -1 else 1
            val digitFraction =
                getDigitFraction(digit = i, isMovingToCenter = distance > 0, fraction = fraction)
            val moveAmountForDigit = dir * distance * digitFraction
            glyphOffsets[i] = moveAmountForDigit

            if (distance > 0) {
                // If distance > 0 then we are moving from the left towards the center. We need to
                // ensure that the glyphs are offset to the initial position.
                glyphOffsets[i] -= dir * distance
            }
        }
        invalidate()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        if (migratedClocks) {
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                textAlignment = TEXT_ALIGNMENT_TEXT_END
            } else {
                textAlignment = TEXT_ALIGNMENT_TEXT_START
            }
        }
        super.onRtlPropertiesChanged(layoutDirection)
    }

    private fun getDigitFraction(digit: Int, isMovingToCenter: Boolean, fraction: Float): Float {
        // The delay for the digit, in terms of fraction.
        // (i.e. the digit should not move during 0.0 - 0.1).
        val delays = if (isMovingToCenter) moveToCenterDelays else moveToSideDelays
        val digitInitialDelay = delays[digit] * MOVE_DIGIT_STEP
        return MOVE_INTERPOLATOR.getInterpolation(
            constrainedMap(
                /* rangeMin= */ 0.0f,
                /* rangeMax= */ 1.0f,
                /* valueMin= */ digitInitialDelay,
                /* valueMax= */ digitInitialDelay + AVAILABLE_ANIMATION_TIME,
                /* value= */ fraction,
            )
        )
    }

    /**
     * DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often. This
     * is a cache optimization to ensure we only recompute the patterns when the inputs change.
     */
    private object Patterns {
        var sClockView12: String? = null
        var sClockView24: String? = null
        var sCacheKey: String? = null

        fun update(context: Context) {
            val locale = Locale.getDefault()
            val clockView12Skel = context.resources.getString(R.string.clock_12hr_format)
            val clockView24Skel = context.resources.getString(R.string.clock_24hr_format)
            val key = "$locale$clockView12Skel$clockView24Skel"
            if (key == sCacheKey) {
                return
            }

            sClockView12 =
                DateFormat.getBestDateTimePattern(locale, clockView12Skel).let {
                    // CLDR insists on adding an AM/PM indicator even though it wasn't in the format
                    // string. The following code removes the AM/PM indicator if we didn't want it.
                    if (!clockView12Skel.contains("a")) {
                        it.replace("a".toRegex(), "").trim { it <= ' ' }
                    } else it
                }

            sClockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel)
            sCacheKey = key
        }
    }

    companion object {
        private val TAG = AnimatableClockView::class.simpleName!!
        private val DEFAULT_LOGGER = Logger(LogcatOnlyMessageBuffer(LogLevel.WARNING), TAG)

        const val ANIMATION_DURATION_FOLD_TO_AOD: Int = 600
        private const val DOUBLE_LINE_FORMAT_12_HOUR = "hh\nmm"
        private const val DOUBLE_LINE_FORMAT_24_HOUR = "HH\nmm"
        private const val DOZE_ANIM_DURATION: Long = 300
        private const val APPEAR_ANIM_DURATION: Long = 833
        private const val CHARGE_ANIM_DURATION_PHASE_0: Long = 500
        private const val CHARGE_ANIM_DURATION_PHASE_1: Long = 1000
        private const val COLOR_ANIM_DURATION: Long = 400
        private const val NUM_CLOCK_FONT_ANIMATION_STEPS = 30

        // Constants for the animation
        private val MOVE_INTERPOLATOR = Interpolators.EMPHASIZED

        // Calculate the positions of all of the digits...
        // Offset each digit by, say, 0.1
        // This means that each digit needs to move over a slice of "fractions", i.e. digit 0 should
        // move from 0.0 - 0.7, digit 1 from 0.1 - 0.8, digit 2 from 0.2 - 0.9, and digit 3
        // from 0.3 - 1.0.
        private const val NUM_DIGITS = 4
        private const val DIGITS_PER_LINE = 2

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
        private const val AVAILABLE_ANIMATION_TIME = 1.0f - MOVE_DIGIT_STEP * (NUM_DIGITS - 1)
    }
}
