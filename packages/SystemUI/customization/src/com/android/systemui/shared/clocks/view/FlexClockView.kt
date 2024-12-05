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

package com.android.systemui.shared.clocks.view

import android.graphics.Canvas
import android.graphics.Point
import android.icu.text.NumberFormat
import android.util.MathUtils.constrainedMap
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.android.app.animation.Interpolators
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.ClockContext
import com.android.systemui.shared.clocks.DigitTranslateAnimator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun clamp(value: Float, minVal: Float, maxVal: Float): Float = max(min(value, maxVal), minVal)

class FlexClockView(clockCtx: ClockContext) : DigitalClockFaceView(clockCtx) {
    override var digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    private val digitLeftTopMap = mutableMapOf<Int, Point>()

    private var maxSingleDigitSize = Point(-1, -1)
    private val lockscreenTranslate = Point(0, 0)
    private var aodTranslate = Point(0, 0)

    // Does the current language have mono vertical size when displaying numerals
    private var isMonoVerticalNumericLineSpacing = true

    init {
        setWillNotDraw(false)
        layoutParams =
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        updateLocale(Locale.getDefault())
    }

    private val digitOffsets = mutableMapOf<Int, Float>()

    override fun addView(child: View?) {
        super.addView(child)
        (child as SimpleDigitalClockTextView).digitTranslateAnimator =
            DigitTranslateAnimator(::invalidate)
    }

    protected override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        maxSingleDigitSize = Point(-1, -1)
        val bottomLocation: (textView: SimpleDigitalClockTextView) -> Int = { textView ->
            if (isMonoVerticalNumericLineSpacing) {
                maxSingleDigitSize.y
            } else {
                (textView.paint.fontMetrics.descent - textView.paint.fontMetrics.ascent).toInt()
            }
        }

        digitalClockTextViewMap.forEach { (_, textView) ->
            textView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            maxSingleDigitSize.x = max(maxSingleDigitSize.x, textView.measuredWidth)
            maxSingleDigitSize.y = max(bottomLocation(textView), textView.measuredHeight)
        }
        aodTranslate = Point(0, 0)
        return Point(
            ((maxSingleDigitSize.x + abs(aodTranslate.x)) * 2),
            ((maxSingleDigitSize.y + abs(aodTranslate.y)) * 2),
        )
    }

    protected override fun calculateLeftTopPosition() {
        digitLeftTopMap[R.id.HOUR_FIRST_DIGIT] = Point(0, 0)
        digitLeftTopMap[R.id.HOUR_SECOND_DIGIT] = Point(maxSingleDigitSize.x, 0)
        digitLeftTopMap[R.id.MINUTE_FIRST_DIGIT] = Point(0, maxSingleDigitSize.y)
        digitLeftTopMap[R.id.MINUTE_SECOND_DIGIT] = Point(maxSingleDigitSize)
        digitLeftTopMap.forEach { (_, point) ->
            point.x += abs(aodTranslate.x)
            point.y += abs(aodTranslate.y)
        }
    }

    override fun refreshTime() {
        super.refreshTime()
        digitalClockTextViewMap.forEach { (_, textView) -> textView.refreshText() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        digitalClockTextViewMap.forEach { (id, textView) ->
            // save canvas location in anticipation of restoration later
            canvas.save()
            val xTranslateAmount =
                digitOffsets.getOrDefault(id, 0f) + digitLeftTopMap[id]!!.x.toFloat()
            // move canvas to location that the textView would like
            canvas.translate(xTranslateAmount, digitLeftTopMap[id]!!.y.toFloat())
            // draw the textView at the location of the canvas above
            textView.draw(canvas)
            // reset the canvas location back to 0 without drawing
            canvas.restore()
        }
    }

    override fun onLocaleChanged(locale: Locale) {
        updateLocale(locale)
        requestLayout()
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        dozeControlState.animateDoze = {
            super.animateDoze(isDozing, isAnimated)
            if (maxSingleDigitSize.x < 0 || maxSingleDigitSize.y < 0) {
                measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            }
            digitalClockTextViewMap.forEach { (id, textView) ->
                textView.digitTranslateAnimator?.let {
                    if (!isDozing) {
                        it.animatePosition(
                            animate = isAnimated && isAnimationEnabled,
                            interpolator = Interpolators.EMPHASIZED,
                            duration = AOD_TRANSITION_DURATION,
                            targetTranslation =
                                updateDirectionalTargetTranslate(id, lockscreenTranslate),
                        )
                    } else {
                        it.animatePosition(
                            animate = isAnimated && isAnimationEnabled,
                            interpolator = Interpolators.EMPHASIZED,
                            duration = AOD_TRANSITION_DURATION,
                            onAnimationEnd = null,
                            targetTranslation = updateDirectionalTargetTranslate(id, aodTranslate),
                        )
                    }
                }
            }
        }
    }

    override fun animateCharge() {
        super.animateCharge()
        digitalClockTextViewMap.forEach { (id, textView) ->
            textView.digitTranslateAnimator?.let {
                it.animatePosition(
                    animate = isAnimationEnabled,
                    interpolator = Interpolators.EMPHASIZED,
                    duration = CHARGING_TRANSITION_DURATION,
                    onAnimationEnd = {
                        it.animatePosition(
                            animate = isAnimationEnabled,
                            interpolator = Interpolators.EMPHASIZED,
                            duration = CHARGING_TRANSITION_DURATION,
                            targetTranslation =
                                updateDirectionalTargetTranslate(
                                    id,
                                    if (dozeFraction == 1F) aodTranslate else lockscreenTranslate,
                                ),
                        )
                    },
                    targetTranslation =
                        updateDirectionalTargetTranslate(
                            id,
                            if (dozeFraction == 1F) lockscreenTranslate else aodTranslate,
                        ),
                )
            }
        }
    }

    private fun updateLocale(locale: Locale) {
        isMonoVerticalNumericLineSpacing =
            !NON_MONO_VERTICAL_NUMERIC_LINE_SPACING_LANGUAGES.any {
                val newLocaleNumberFormat =
                    NumberFormat.getInstance(locale).format(FORMAT_NUMBER.toLong())
                val nonMonoVerticalNumericLineSpaceNumberFormat =
                    NumberFormat.getInstance(Locale.forLanguageTag(it))
                        .format(FORMAT_NUMBER.toLong())
                newLocaleNumberFormat == nonMonoVerticalNumericLineSpaceNumberFormat
            }
    }

    /**
     * Offsets the textViews of the clock for the step clock animation.
     *
     * The animation makes the textViews of the clock move at different speeds, when the clock is
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
            val mapIndexToId =
                when (i) {
                    0 -> R.id.HOUR_FIRST_DIGIT
                    1 -> R.id.HOUR_SECOND_DIGIT
                    2 -> R.id.MINUTE_FIRST_DIGIT
                    3 -> R.id.MINUTE_SECOND_DIGIT
                    else -> -1
                }
            val digitFraction =
                getDigitFraction(
                    digit = i,
                    isMovingToCenter = isMovingToCenter,
                    fraction = moveFraction,
                )
            // left here is the final left position after the animation is done
            val moveAmountForDigit = currentMoveAmount * digitFraction
            var moveAmountDeltaForDigit = moveAmountForDigit - currentMoveAmount
            if (isMovingToCenter && moveAmountForDigit < 0) moveAmountDeltaForDigit *= -1
            digitOffsets[mapIndexToId] = moveAmountDeltaForDigit
            invalidate()
        }
    }

    private val moveToCenterDelays: List<Int>
        get() = if (isLayoutRtl) MOVE_LEFT_DELAYS else MOVE_RIGHT_DELAYS

    private val moveToSideDelays: List<Int>
        get() = if (isLayoutRtl) MOVE_RIGHT_DELAYS else MOVE_LEFT_DELAYS

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

    companion object {
        val AOD_TRANSITION_DURATION = 750L
        val CHARGING_TRANSITION_DURATION = 300L

        // Calculate the positions of all of the digits...
        // Offset each digit by, say, 0.1
        // This means that each digit needs to move over a slice of "fractions", i.e. digit 0 should
        // move from 0.0 - 0.7, digit 1 from 0.1 - 0.8, digit 2 from 0.2 - 0.9, and digit 3
        // from 0.3 - 1.0.
        private const val NUM_DIGITS = 4

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

        // Constants for the animation
        private val MOVE_INTERPOLATOR = Interpolators.EMPHASIZED

        private const val FORMAT_NUMBER = 1234567890

        // Total available transition time for each digit, taking into account the step. If step is
        // 0.1, then digit 0 would animate over 0.0 - 0.7, making availableTime 0.7.
        private const val AVAILABLE_ANIMATION_TIME = 1.0f - MOVE_DIGIT_STEP * (NUM_DIGITS - 1)

        // Add language tags below that do not have vertically mono spaced numerals
        private val NON_MONO_VERTICAL_NUMERIC_LINE_SPACING_LANGUAGES =
            setOf(
                "my", // Burmese
            )

        // Use the sign of targetTranslation to control the direction of digit translation
        fun updateDirectionalTargetTranslate(id: Int, targetTranslation: Point): Point {
            val outPoint = Point(targetTranslation)
            when (id) {
                R.id.HOUR_FIRST_DIGIT -> {
                    outPoint.x *= -1
                    outPoint.y *= -1
                }
                R.id.HOUR_SECOND_DIGIT -> {
                    outPoint.x *= 1
                    outPoint.y *= -1
                }
                R.id.MINUTE_FIRST_DIGIT -> {
                    outPoint.x *= -1
                    outPoint.y *= 1
                }
                R.id.MINUTE_SECOND_DIGIT -> {
                    outPoint.x *= 1
                    outPoint.y *= 1
                }
            }
            return outPoint
        }
    }
}
