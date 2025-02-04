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

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils
import android.util.TypedValue
import android.view.View.MeasureSpec.EXACTLY
import android.view.animation.Interpolator
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.animation.GSFAxes
import com.android.systemui.animation.TextAnimator
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.ClockFontAxisSetting
import com.android.systemui.plugins.clocks.ClockFontAxisSetting.Companion.replace
import com.android.systemui.plugins.clocks.ClockFontAxisSetting.Companion.toFVar
import com.android.systemui.plugins.clocks.ClockLogger
import com.android.systemui.shared.clocks.ClockContext
import com.android.systemui.shared.clocks.DigitTranslateAnimator
import com.android.systemui.shared.clocks.DimensionParser
import com.android.systemui.shared.clocks.FLEX_CLOCK_ID
import com.android.systemui.shared.clocks.FontTextStyle
import java.lang.Thread
import kotlin.math.max
import kotlin.math.min

private val TAG = SimpleDigitalClockTextView::class.simpleName!!

enum class VerticalAlignment {
    TOP,
    BOTTOM,
    BASELINE, // default
    CENTER,
}

enum class HorizontalAlignment {
    LEFT,
    RIGHT,
    CENTER, // default
}

@SuppressLint("AppCompatCustomView")
open class SimpleDigitalClockTextView(
    clockCtx: ClockContext,
    isLargeClock: Boolean,
    attrs: AttributeSet? = null,
) : TextView(clockCtx.context, attrs) {
    val lockScreenPaint = TextPaint()
    lateinit var textStyle: FontTextStyle
    lateinit var aodStyle: FontTextStyle

    private val isLegacyFlex = clockCtx.settings.clockId == FLEX_CLOCK_ID
    private val fixedAodAxes =
        when {
            !isLegacyFlex -> listOf(AOD_WEIGHT_AXIS, WIDTH_AXIS)
            isLargeClock -> listOf(FLEX_AOD_LARGE_WEIGHT_AXIS, FLEX_AOD_WIDTH_AXIS)
            else -> listOf(FLEX_AOD_SMALL_WEIGHT_AXIS, FLEX_AOD_WIDTH_AXIS)
        }

    private var lsFontVariation =
        if (!isLegacyFlex) listOf(LS_WEIGHT_AXIS, WIDTH_AXIS, ROUND_AXIS, SLANT_AXIS).toFVar()
        else listOf(FLEX_LS_WEIGHT_AXIS, FLEX_LS_WIDTH_AXIS, FLEX_ROUND_AXIS, SLANT_AXIS).toFVar()

    private var aodFontVariation = run {
        val roundAxis = if (!isLegacyFlex) ROUND_AXIS else FLEX_ROUND_AXIS
        (fixedAodAxes + listOf(roundAxis, SLANT_AXIS)).toFVar()
    }

    private val parser = DimensionParser(clockCtx.context)
    var maxSingleDigitHeight = -1
    var maxSingleDigitWidth = -1
    var digitTranslateAnimator: DigitTranslateAnimator? = null
    var aodFontSizePx: Float = -1F

    // Store the font size when there's no height constraint as a reference when adjusting font size
    private var lastUnconstrainedTextSize: Float = Float.MAX_VALUE
    // Calculated by height of styled text view / text size
    // Used as a factor to calculate a smaller font size when text height is constrained
    @VisibleForTesting var fontSizeAdjustFactor = 1F

    private val initThread = Thread.currentThread()

    // textBounds is the size of text in LS, which only measures current text in lockscreen style
    var textBounds = Rect()
    // prevTextBounds and targetTextBounds are to deal with dozing animation between LS and AOD
    // especially for the textView which has different bounds during the animation
    // prevTextBounds holds the state we are transitioning from
    private val prevTextBounds = Rect()
    // targetTextBounds holds the state we are interpolating to
    private val targetTextBounds = Rect()
    protected val logger = ClockLogger(this, clockCtx.messageBuffer, this::class.simpleName!!)
        get() = field ?: ClockLogger.INIT_LOGGER

    private var aodDozingInterpolator: Interpolator? = null

    @VisibleForTesting lateinit var textAnimator: TextAnimator

    private val typefaceCache = clockCtx.typefaceCache.getVariantCache("")

    @VisibleForTesting
    var textAnimatorFactory: (Layout, () -> Unit) -> TextAnimator = { layout, invalidateCb ->
        TextAnimator(layout, typefaceCache, invalidateCb)
    }

    var verticalAlignment: VerticalAlignment = VerticalAlignment.BASELINE
    var horizontalAlignment: HorizontalAlignment = HorizontalAlignment.LEFT
    var isAnimationEnabled = true
    var dozeFraction: Float = 0F
        set(value) {
            field = value
            invalidate()
        }

    var textBorderWidth = 0F
    var baselineFromMeasure = 0
    var lockscreenColor = Color.WHITE

    fun updateColor(color: Int) {
        lockscreenColor = color
        lockScreenPaint.color = lockscreenColor
        if (dozeFraction < 1f) {
            textAnimator.setTextStyle(color = lockscreenColor, animate = false)
        }
        invalidate()
    }

    fun updateAxes(lsAxes: List<ClockFontAxisSetting>) {
        lsFontVariation = lsAxes.toFVar()
        aodFontVariation = lsAxes.replace(fixedAodAxes).toFVar()
        logger.i({ "updateAxes(LS = $str1, AOD = $str2)" }) {
            str1 = lsFontVariation
            str2 = aodFontVariation
        }

        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(lsFontVariation)
        typeface = lockScreenPaint.typeface

        lockScreenPaint.getTextBounds(text, 0, text.length, textBounds)
        targetTextBounds.set(textBounds)

        textAnimator.setTextStyle(fvar = lsFontVariation, animate = false)
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        recomputeMaxSingleDigitSizes()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.onMeasure()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val layout = this.layout
        if (layout != null) {
            if (!this::textAnimator.isInitialized) {
                textAnimator = textAnimatorFactory(layout, ::invalidate)
                setInterpolatorPaint()
            } else {
                textAnimator.updateLayout(layout)
            }
            baselineFromMeasure = layout.getLineBaseline(0)
        } else {
            val currentThread = Thread.currentThread()
            Log.wtf(
                TAG,
                "TextView.getLayout() is null after measure! " +
                    "currentThread=$currentThread; initThread=$initThread",
            )
        }

        var expectedWidth: Int
        var expectedHeight: Int

        if (MeasureSpec.getMode(heightMeasureSpec) == EXACTLY) {
            // For view which has fixed height, e.g. small clock,
            // we should always return the size required from parent view
            expectedHeight = heightMeasureSpec
        } else {
            expectedHeight =
                MeasureSpec.makeMeasureSpec(
                    if (isSingleDigit()) {
                        maxSingleDigitHeight
                    } else {
                        textBounds.height() + 2 * lockScreenPaint.strokeWidth.toInt()
                    },
                    MeasureSpec.getMode(measuredHeightAndState),
                )
        }

        if (MeasureSpec.getMode(widthMeasureSpec) == EXACTLY) {
            expectedWidth = widthMeasureSpec
        } else {
            expectedWidth =
                MeasureSpec.makeMeasureSpec(
                    if (isSingleDigit()) {
                        maxSingleDigitWidth
                    } else {
                        max(
                            textBounds.width() + 2 * lockScreenPaint.strokeWidth.toInt(),
                            MeasureSpec.getSize(measuredWidthAndState),
                        )
                    },
                    MeasureSpec.getMode(measuredWidthAndState),
                )
        }

        setMeasuredDimension(expectedWidth, expectedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        logger.onDraw(textAnimator.textInterpolator.shapedText)

        val translation = getLocalTranslation()
        canvas.translate(translation.x.toFloat(), translation.y.toFloat())
        digitTranslateAnimator?.let {
            canvas.translate(it.updatedTranslate.x.toFloat(), it.updatedTranslate.y.toFloat())
        }

        textAnimator.draw(canvas)

        digitTranslateAnimator?.let {
            canvas.translate(-it.updatedTranslate.x.toFloat(), -it.updatedTranslate.y.toFloat())
        }
        canvas.translate(-translation.x.toFloat(), -translation.y.toFloat())
    }

    override fun setVisibility(visibility: Int) {
        logger.setVisibility(visibility)
        super.setVisibility(visibility)
    }

    override fun setAlpha(alpha: Float) {
        logger.setAlpha(alpha)
        super.setAlpha(alpha)
    }

    override fun invalidate() {
        logger.invalidate()
        super.invalidate()
        (parent as? FlexClockView)?.invalidate()
    }

    fun refreshTime() {
        logger.refreshTime()
        refreshText()
    }

    fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        if (!this::textAnimator.isInitialized) return
        textAnimator.setTextStyle(
            animate = isAnimated && isAnimationEnabled,
            color = if (isDozing) AOD_COLOR else lockscreenColor,
            textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
            fvar = if (isDozing) aodFontVariation else lsFontVariation,
            duration = aodStyle.transitionDuration,
            interpolator = aodDozingInterpolator,
        )
        updateTextBoundsForTextAnimator()
    }

    fun animateCharge() {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning()) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        logger.d("animateCharge()")
        val startAnimPhase2 = Runnable {
            textAnimator.setTextStyle(
                fvar = if (dozeFraction == 0F) lsFontVariation else aodFontVariation,
                animate = isAnimationEnabled,
            )
            updateTextBoundsForTextAnimator()
        }
        textAnimator.setTextStyle(
            fvar = if (dozeFraction == 0F) aodFontVariation else lsFontVariation,
            animate = isAnimationEnabled,
            onAnimationEnd = startAnimPhase2,
        )
        updateTextBoundsForTextAnimator()
    }

    fun refreshText() {
        lockScreenPaint.getTextBounds(text, 0, text.length, textBounds)
        if (this::textAnimator.isInitialized) {
            textAnimator.textInterpolator.targetPaint.getTextBounds(
                text,
                0,
                text.length,
                targetTextBounds,
            )
        }

        if (layout == null) {
            requestLayout()
        } else {
            textAnimator.updateLayout(layout)
        }
    }

    private fun isSingleDigit(): Boolean {
        return id == R.id.HOUR_FIRST_DIGIT ||
            id == R.id.HOUR_SECOND_DIGIT ||
            id == R.id.MINUTE_FIRST_DIGIT ||
            id == R.id.MINUTE_SECOND_DIGIT
    }

    private fun updateInterpolatedTextBounds(): Rect {
        val interpolatedTextBounds = Rect()
        if (textAnimator.animator.animatedFraction != 1.0f && textAnimator.animator.isRunning) {
            interpolatedTextBounds.left =
                MathUtils.lerp(
                        prevTextBounds.left,
                        targetTextBounds.left,
                        textAnimator.animator.animatedValue as Float,
                    )
                    .toInt()

            interpolatedTextBounds.right =
                MathUtils.lerp(
                        prevTextBounds.right,
                        targetTextBounds.right,
                        textAnimator.animator.animatedValue as Float,
                    )
                    .toInt()

            interpolatedTextBounds.top =
                MathUtils.lerp(
                        prevTextBounds.top,
                        targetTextBounds.top,
                        textAnimator.animator.animatedValue as Float,
                    )
                    .toInt()

            interpolatedTextBounds.bottom =
                MathUtils.lerp(
                        prevTextBounds.bottom,
                        targetTextBounds.bottom,
                        textAnimator.animator.animatedValue as Float,
                    )
                    .toInt()
        } else {
            interpolatedTextBounds.set(targetTextBounds)
        }
        return interpolatedTextBounds
    }

    private fun updateXtranslation(inPoint: Point, interpolatedTextBounds: Rect): Point {
        when (horizontalAlignment) {
            HorizontalAlignment.LEFT -> {
                inPoint.x = lockScreenPaint.strokeWidth.toInt() - interpolatedTextBounds.left
            }
            HorizontalAlignment.RIGHT -> {
                inPoint.x =
                    measuredWidth -
                        interpolatedTextBounds.right -
                        lockScreenPaint.strokeWidth.toInt()
            }
            HorizontalAlignment.CENTER -> {
                inPoint.x =
                    (measuredWidth - interpolatedTextBounds.width()) / 2 -
                        interpolatedTextBounds.left
            }
        }
        return inPoint
    }

    // translation of reference point of text
    // used for translation when calling textInterpolator
    private fun getLocalTranslation(): Point {
        val interpolatedTextBounds = updateInterpolatedTextBounds()
        val localTranslation = Point(0, 0)
        val correctedBaseline = if (baseline != -1) baseline else baselineFromMeasure
        // get the change from current baseline to expected baseline
        when (verticalAlignment) {
            VerticalAlignment.CENTER -> {
                localTranslation.y =
                    ((measuredHeight - interpolatedTextBounds.height()) / 2 -
                        interpolatedTextBounds.top -
                        correctedBaseline)
            }
            VerticalAlignment.TOP -> {
                localTranslation.y =
                    (-interpolatedTextBounds.top + lockScreenPaint.strokeWidth - correctedBaseline)
                        .toInt()
            }
            VerticalAlignment.BOTTOM -> {
                localTranslation.y =
                    measuredHeight -
                        interpolatedTextBounds.bottom -
                        lockScreenPaint.strokeWidth.toInt() -
                        correctedBaseline
            }
            VerticalAlignment.BASELINE -> {
                // account for max bottom distance of font, so clock doesn't collide with elements
                localTranslation.y =
                    -lockScreenPaint.strokeWidth.toInt() - paint.fontMetrics.descent.toInt()
            }
        }

        return updateXtranslation(localTranslation, interpolatedTextBounds)
    }

    fun applyStyles(textStyle: FontTextStyle, aodStyle: FontTextStyle?) {
        this.textStyle = textStyle
        lockScreenPaint.strokeJoin = Paint.Join.ROUND
        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(lsFontVariation)
        typeface = lockScreenPaint.typeface
        textStyle.lineHeight?.let { lineHeight = it.toInt() }

        this.aodStyle = aodStyle ?: textStyle.copy()
        this.aodStyle.transitionInterpolator?.let { aodDozingInterpolator = it }
        lockScreenPaint.strokeWidth = textBorderWidth
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setInterpolatorPaint()
        recomputeMaxSingleDigitSizes()
        invalidate()
    }

    // When constrainedByHeight is on, targetFontSizePx is the constrained height of textView
    fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean = false) {
        val adjustedFontSizePx = adjustFontSize(targetFontSizePx, constrainedByHeight)
        val fontSizePx = adjustedFontSizePx * (textStyle.fontSizeScale ?: 1f)
        aodFontSizePx =
            adjustedFontSizePx * (aodStyle.fontSizeScale ?: textStyle.fontSizeScale ?: 1f)
        if (fontSizePx > 0) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            lockScreenPaint.textSize = textSize
            lockScreenPaint.getTextBounds(text, 0, text.length, textBounds)
            targetTextBounds.set(textBounds)
        }
        if (!constrainedByHeight) {
            val lastUnconstrainedHeight = textBounds.height() + lockScreenPaint.strokeWidth * 2
            fontSizeAdjustFactor = lastUnconstrainedHeight / lastUnconstrainedTextSize
        }

        lockScreenPaint.strokeWidth = textBorderWidth
        recomputeMaxSingleDigitSizes()

        if (this::textAnimator.isInitialized) {
            textAnimator.setTextStyle(textSize = lockScreenPaint.textSize, animate = false)
        }
    }

    private fun recomputeMaxSingleDigitSizes() {
        val rectForCalculate = Rect()
        maxSingleDigitHeight = 0
        maxSingleDigitWidth = 0

        for (i in 0..9) {
            lockScreenPaint.getTextBounds("$i", 0, 1, rectForCalculate)
            maxSingleDigitHeight = max(maxSingleDigitHeight, rectForCalculate.height())
            maxSingleDigitWidth = max(maxSingleDigitWidth, rectForCalculate.width())
        }
        maxSingleDigitWidth += 2 * lockScreenPaint.strokeWidth.toInt()
        maxSingleDigitHeight += 2 * lockScreenPaint.strokeWidth.toInt()
    }

    // called without animation, can be used to set the initial state of animator
    private fun setInterpolatorPaint() {
        if (this::textAnimator.isInitialized) {
            // set initial style
            textAnimator.textInterpolator.targetPaint.set(lockScreenPaint)
            textAnimator.textInterpolator.onTargetPaintModified()
            textAnimator.setTextStyle(
                fvar = lsFontVariation,
                textSize = lockScreenPaint.textSize,
                color = lockscreenColor,
                animate = false,
            )
        }
    }

    /* Called after textAnimator.setTextStyle
     * textAnimator.setTextStyle will update targetPaint,
     * and rebase if previous animator is canceled
     * so basePaint will store the state we transition from
     * and targetPaint will store the state we transition to
     */
    private fun updateTextBoundsForTextAnimator() {
        textAnimator.textInterpolator.basePaint.getTextBounds(text, 0, text.length, prevTextBounds)
        textAnimator.textInterpolator.targetPaint.getTextBounds(
            text,
            0,
            text.length,
            targetTextBounds,
        )
    }

    /*
     * Adjust text size to adapt to large display / font size
     * where the text view will be constrained by height
     */
    private fun adjustFontSize(targetFontSizePx: Float?, constrainedByHeight: Boolean): Float {
        return if (constrainedByHeight) {
            min((targetFontSizePx ?: 0F) / fontSizeAdjustFactor, lastUnconstrainedTextSize)
        } else {
            lastUnconstrainedTextSize = targetFontSizePx ?: 1F
            lastUnconstrainedTextSize
        }
    }

    companion object {
        private val PORTER_DUFF_XFER_MODE_PAINT =
            Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

        val AOD_COLOR = Color.WHITE
        val LS_WEIGHT_AXIS = ClockFontAxisSetting(GSFAxes.WEIGHT, 400f)
        val AOD_WEIGHT_AXIS = ClockFontAxisSetting(GSFAxes.WEIGHT, 200f)
        val WIDTH_AXIS = ClockFontAxisSetting(GSFAxes.WIDTH, 85f)
        val ROUND_AXIS = ClockFontAxisSetting(GSFAxes.ROUND, 0f)
        val SLANT_AXIS = ClockFontAxisSetting(GSFAxes.SLANT, 0f)

        // Axes for Legacy version of the Flex Clock
        val FLEX_LS_WEIGHT_AXIS = ClockFontAxisSetting(GSFAxes.WEIGHT, 600f)
        val FLEX_AOD_LARGE_WEIGHT_AXIS = ClockFontAxisSetting(GSFAxes.WEIGHT, 74f)
        val FLEX_AOD_SMALL_WEIGHT_AXIS = ClockFontAxisSetting(GSFAxes.WEIGHT, 133f)
        val FLEX_LS_WIDTH_AXIS = ClockFontAxisSetting(GSFAxes.WIDTH, 100f)
        val FLEX_AOD_WIDTH_AXIS = ClockFontAxisSetting(GSFAxes.WIDTH, 43f)
        val FLEX_ROUND_AXIS = ClockFontAxisSetting(GSFAxes.ROUND, 100f)
    }
}
