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
import android.content.Context
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
import android.view.View.MeasureSpec.AT_MOST
import android.view.View.MeasureSpec.EXACTLY
import android.view.animation.Interpolator
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.animation.TextAnimator
import com.android.systemui.animation.TypefaceVariantCache
import com.android.systemui.customization.R
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.shared.clocks.AssetLoader
import com.android.systemui.shared.clocks.ClockAnimation
import com.android.systemui.shared.clocks.DigitTranslateAnimator
import com.android.systemui.shared.clocks.DimensionParser
import com.android.systemui.shared.clocks.FontTextStyle
import com.android.systemui.shared.clocks.LogUtil
import com.android.systemui.shared.clocks.RenderType
import com.android.systemui.shared.clocks.TextStyle
import java.lang.Thread
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val TAG = SimpleDigitalClockTextView::class.simpleName!!

@SuppressLint("AppCompatCustomView")
open class SimpleDigitalClockTextView(
    ctx: Context,
    messageBuffer: MessageBuffer,
    attrs: AttributeSet? = null,
) : TextView(ctx, attrs), SimpleDigitalClockView {
    val lockScreenPaint = TextPaint()
    override lateinit var textStyle: FontTextStyle
    lateinit var aodStyle: FontTextStyle
    private val parser = DimensionParser(ctx)
    var maxSingleDigitHeight = -1
    var maxSingleDigitWidth = -1
    var digitTranslateAnimator: DigitTranslateAnimator? = null
    var aodFontSizePx: Float = -1F
    var isVertical: Boolean = false

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
    protected val logger = Logger(messageBuffer, this::class.simpleName!!)
        get() = field ?: LogUtil.FALLBACK_INIT_LOGGER

    private var aodDozingInterpolator: Interpolator? = null

    @VisibleForTesting lateinit var textAnimator: TextAnimator
    @VisibleForTesting var outlineAnimator: TextAnimator? = null
    // used for hollow style for AOD version
    // because stroke style for some fonts have some unwanted inner strokes
    // we want to draw this layer on top to oclude them
    @VisibleForTesting var innerAnimator: TextAnimator? = null

    lateinit var typefaceCache: TypefaceVariantCache
        private set

    private fun setTypefaceCache(value: TypefaceVariantCache) {
        typefaceCache = value
        if (this::textAnimator.isInitialized) {
            textAnimator.typefaceCache = value
        }
        outlineAnimator?.typefaceCache = value
        innerAnimator?.typefaceCache = value
    }

    @VisibleForTesting
    var textAnimatorFactory: (Layout, () -> Unit) -> TextAnimator = { layout, invalidateCb ->
        TextAnimator(layout, ClockAnimation.NUM_CLOCK_FONT_ANIMATION_STEPS, invalidateCb).also {
            if (this::typefaceCache.isInitialized) {
                it.typefaceCache = typefaceCache
            }
        }
    }

    override var verticalAlignment: VerticalAlignment = VerticalAlignment.CENTER
    override var horizontalAlignment: HorizontalAlignment = HorizontalAlignment.LEFT
    override var isAnimationEnabled = true
    override var dozeFraction: Float = 0F
        set(value) {
            field = value
            invalidate()
        }

    // Have to passthrough to unify View with SimpleDigitalClockView
    override var text: String
        get() = super.getText().toString()
        set(value) = super.setText(value)

    var textBorderWidth = 0F
    var aodBorderWidth = 0F
    var baselineFromMeasure = 0

    var textFillColor: Int? = null
    var textOutlineColor = TEXT_OUTLINE_DEFAULT_COLOR
    var aodFillColor = AOD_DEFAULT_COLOR
    var aodOutlineColor = AOD_OUTLINE_DEFAULT_COLOR

    override fun updateColors(assets: AssetLoader, isRegionDark: Boolean) {
        val fillColor = if (isRegionDark) textStyle.fillColorLight else textStyle.fillColorDark
        textFillColor =
            fillColor?.let { assets.readColor(it) }
                ?: assets.seedColor
                ?: getDefaultColor(assets, isRegionDark)
        // for NumberOverlapView to read correct color
        lockScreenPaint.color = textFillColor as Int
        textStyle.outlineColor?.let { textOutlineColor = assets.readColor(it) }
            ?: run { textOutlineColor = TEXT_OUTLINE_DEFAULT_COLOR }
        (aodStyle.fillColorLight ?: aodStyle.fillColorDark)?.let {
            aodFillColor = assets.readColor(it)
        } ?: run { aodFillColor = AOD_DEFAULT_COLOR }
        aodStyle.outlineColor?.let { aodOutlineColor = assets.readColor(it) }
            ?: run { aodOutlineColor = AOD_OUTLINE_DEFAULT_COLOR }
        if (dozeFraction < 1f) {
            textAnimator.setTextStyle(color = textFillColor, animate = false)
            outlineAnimator?.setTextStyle(color = textOutlineColor, animate = false)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.d("onMeasure()")
        if (isVertical) {
            // use at_most to avoid apply measuredWidth from last measuring to measuredHeight
            // cause we use max to setMeasuredDimension
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), AT_MOST),
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), AT_MOST),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        val layout = this.layout
        if (layout != null) {
            if (!this::textAnimator.isInitialized) {
                textAnimator = textAnimatorFactory(layout, ::invalidate)
                outlineAnimator = textAnimatorFactory(layout) {}
                innerAnimator = textAnimatorFactory(layout) {}
                setInterpolatorPaint()
            } else {
                textAnimator.updateLayout(layout)
                outlineAnimator?.updateLayout(layout)
                innerAnimator?.updateLayout(layout)
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
                    MeasureSpec.getMode(measuredHeight),
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
                            MeasureSpec.getSize(measuredWidth),
                        )
                    },
                    MeasureSpec.getMode(measuredWidth),
                )
        }

        if (isVertical) {
            expectedWidth = expectedHeight.also { expectedHeight = expectedWidth }
        }
        setMeasuredDimension(expectedWidth, expectedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (isVertical) {
            canvas.save()
            canvas.translate(0F, measuredHeight.toFloat())
            canvas.rotate(-90F)
        }
        logger.d({ "onDraw(); ls: $str1; aod: $str2;" }) {
            str1 = textAnimator.textInterpolator.shapedText
            str2 = outlineAnimator?.textInterpolator?.shapedText
        }
        val translation = getLocalTranslation()
        canvas.translate(translation.x.toFloat(), translation.y.toFloat())
        digitTranslateAnimator?.let {
            canvas.translate(it.updatedTranslate.x.toFloat(), it.updatedTranslate.y.toFloat())
        }

        if (aodStyle.renderType == RenderType.HOLLOW_TEXT) {
            canvas.saveLayer(
                -translation.x.toFloat(),
                -translation.y.toFloat(),
                (-translation.x + measuredWidth).toFloat(),
                (-translation.y + measuredHeight).toFloat(),
                null,
            )
            outlineAnimator?.draw(canvas)
            canvas.saveLayer(
                -translation.x.toFloat(),
                -translation.y.toFloat(),
                (-translation.x + measuredWidth).toFloat(),
                (-translation.y + measuredHeight).toFloat(),
                Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) },
            )
            innerAnimator?.draw(canvas)
            canvas.restore()
            canvas.restore()
        } else if (aodStyle.renderType != RenderType.CHANGE_WEIGHT) {
            outlineAnimator?.draw(canvas)
        }
        textAnimator.draw(canvas)

        digitTranslateAnimator?.let {
            canvas.translate(-it.updatedTranslate.x.toFloat(), -it.updatedTranslate.y.toFloat())
        }
        canvas.translate(-translation.x.toFloat(), -translation.y.toFloat())
        if (isVertical) {
            canvas.restore()
        }
    }

    override fun invalidate() {
        logger.d("invalidate()")
        super.invalidate()
        (parent as? DigitalClockFaceView)?.invalidate()
    }

    override fun refreshTime() {
        logger.d("refreshTime()")
        refreshText()
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        if (!this::textAnimator.isInitialized) {
            return
        }
        val fvar = if (isDozing) aodStyle.fontVariation else textStyle.fontVariation
        textAnimator.setTextStyle(
            animate = isAnimated && isAnimationEnabled,
            color = if (isDozing) aodFillColor else textFillColor,
            textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
            fvar = fvar,
            duration = aodStyle.transitionDuration,
            interpolator = aodDozingInterpolator,
        )
        updateTextBoundsForTextAnimator()
        outlineAnimator?.setTextStyle(
            animate = isAnimated && isAnimationEnabled,
            color = if (isDozing) aodOutlineColor else textOutlineColor,
            textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
            fvar = fvar,
            strokeWidth = if (isDozing) aodBorderWidth else textBorderWidth,
            duration = aodStyle.transitionDuration,
            interpolator = aodDozingInterpolator,
        )
        innerAnimator?.setTextStyle(
            animate = isAnimated && isAnimationEnabled,
            color = Color.WHITE,
            textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
            fvar = fvar,
            duration = aodStyle.transitionDuration,
            interpolator = aodDozingInterpolator,
        )
    }

    override fun animateCharge() {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning()) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        logger.d("animateCharge()")
        val middleFvar = if (dozeFraction == 0F) aodStyle.fontVariation else textStyle.fontVariation
        val endFvar = if (dozeFraction == 0F) textStyle.fontVariation else aodStyle.fontVariation
        val startAnimPhase2 = Runnable {
            textAnimator.setTextStyle(fvar = endFvar, animate = isAnimationEnabled)
            outlineAnimator?.setTextStyle(fvar = endFvar, animate = isAnimationEnabled)
            innerAnimator?.setTextStyle(fvar = endFvar, animate = isAnimationEnabled)
            updateTextBoundsForTextAnimator()
        }
        textAnimator.setTextStyle(
            fvar = middleFvar,
            animate = isAnimationEnabled,
            onAnimationEnd = startAnimPhase2,
        )
        outlineAnimator?.setTextStyle(fvar = middleFvar, animate = isAnimationEnabled)
        innerAnimator?.setTextStyle(fvar = middleFvar, animate = isAnimationEnabled)
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
            outlineAnimator?.updateLayout(layout)
            innerAnimator?.updateLayout(layout)
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
        val viewWidth = if (isVertical) measuredHeight else measuredWidth
        when (horizontalAlignment) {
            HorizontalAlignment.LEFT -> {
                inPoint.x = lockScreenPaint.strokeWidth.toInt() - interpolatedTextBounds.left
            }
            HorizontalAlignment.RIGHT -> {
                inPoint.x =
                    viewWidth - interpolatedTextBounds.right - lockScreenPaint.strokeWidth.toInt()
            }
            HorizontalAlignment.CENTER -> {
                inPoint.x =
                    (viewWidth - interpolatedTextBounds.width()) / 2 - interpolatedTextBounds.left
            }
        }
        return inPoint
    }

    // translation of reference point of text
    // used for translation when calling textInterpolator
    fun getLocalTranslation(): Point {
        val viewHeight = if (isVertical) measuredWidth else measuredHeight
        val interpolatedTextBounds = updateInterpolatedTextBounds()
        val localTranslation = Point(0, 0)
        val correctedBaseline = if (baseline != -1) baseline else baselineFromMeasure
        // get the change from current baseline to expected baseline
        when (verticalAlignment) {
            VerticalAlignment.CENTER -> {
                localTranslation.y =
                    ((viewHeight - interpolatedTextBounds.height()) / 2 -
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
                    viewHeight -
                        interpolatedTextBounds.bottom -
                        lockScreenPaint.strokeWidth.toInt() -
                        correctedBaseline
            }
            VerticalAlignment.BASELINE -> {
                localTranslation.y = -lockScreenPaint.strokeWidth.toInt()
            }
        }

        return updateXtranslation(localTranslation, interpolatedTextBounds)
    }

    override fun applyStyles(assets: AssetLoader, textStyle: TextStyle, aodStyle: TextStyle?) {
        this.textStyle = textStyle as FontTextStyle
        val typefaceName = "fonts/" + textStyle.fontFamily
        setTypefaceCache(assets.typefaceCache.getVariantCache(typefaceName))
        lockScreenPaint.strokeJoin = Paint.Join.ROUND
        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(textStyle.fontVariation)
        textStyle.fontFeatureSettings?.let {
            lockScreenPaint.fontFeatureSettings = it
            fontFeatureSettings = it
        }
        typeface = lockScreenPaint.typeface
        textStyle.lineHeight?.let { lineHeight = it.toInt() }
        // borderWidth in textStyle and aodStyle is used to draw,
        // strokeWidth in lockScreenPaint is used to measure and get enough space for the text
        textStyle.borderWidth?.let { textBorderWidth = parser.convert(it) }

        if (aodStyle != null && aodStyle is FontTextStyle) {
            this.aodStyle = aodStyle
        } else {
            this.aodStyle = textStyle.copy()
        }
        this.aodStyle.transitionInterpolator?.let { aodDozingInterpolator = it.interpolator }
        aodBorderWidth = parser.convert(this.aodStyle.borderWidth ?: DEFAULT_AOD_STROKE_WIDTH)
        lockScreenPaint.strokeWidth = ceil(max(textBorderWidth, aodBorderWidth))
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setInterpolatorPaint()
        recomputeMaxSingleDigitSizes()
        invalidate()
    }

    // When constrainedByHeight is on, targetFontSizePx is the constrained height of textView
    override fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean) {
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
        textStyle.borderWidthScale?.let {
            textBorderWidth = fontSizePx * it
            if (dozeFraction < 1.0F) {
                outlineAnimator?.setTextStyle(strokeWidth = textBorderWidth, animate = false)
            }
        }
        aodStyle.borderWidthScale?.let {
            aodBorderWidth = fontSizePx * it
            if (dozeFraction > 0.0F) {
                outlineAnimator?.setTextStyle(strokeWidth = aodBorderWidth, animate = false)
            }
        }

        lockScreenPaint.strokeWidth = ceil(max(textBorderWidth, aodBorderWidth))
        recomputeMaxSingleDigitSizes()

        if (this::textAnimator.isInitialized) {
            textAnimator.setTextStyle(textSize = lockScreenPaint.textSize, animate = false)
        }
        outlineAnimator?.setTextStyle(textSize = lockScreenPaint.textSize, animate = false)
        innerAnimator?.setTextStyle(textSize = lockScreenPaint.textSize, animate = false)
    }

    private fun recomputeMaxSingleDigitSizes() {
        val rectForCalculate = Rect()
        maxSingleDigitHeight = 0
        maxSingleDigitWidth = 0

        for (i in 0..9) {
            lockScreenPaint.getTextBounds(i.toString(), 0, 1, rectForCalculate)
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
                fvar = textStyle.fontVariation,
                textSize = lockScreenPaint.textSize,
                color = textFillColor,
                animate = false,
            )
        }

        if (outlineAnimator != null) {
            outlineAnimator!!
                .textInterpolator
                .targetPaint
                .set(
                    TextPaint(lockScreenPaint).also {
                        it.style =
                            if (aodStyle.renderType == RenderType.HOLLOW_TEXT)
                                Paint.Style.FILL_AND_STROKE
                            else Paint.Style.STROKE
                    }
                )
            outlineAnimator!!.textInterpolator.onTargetPaintModified()
            outlineAnimator!!.setTextStyle(
                fvar = aodStyle.fontVariation,
                textSize = lockScreenPaint.textSize,
                color = Color.TRANSPARENT,
                animate = false,
            )
        }

        if (innerAnimator != null) {
            innerAnimator!!
                .textInterpolator
                .targetPaint
                .set(TextPaint(lockScreenPaint).also { it.style = Paint.Style.FILL })
            innerAnimator!!.textInterpolator.onTargetPaintModified()
            innerAnimator!!.setTextStyle(
                fvar = aodStyle.fontVariation,
                textSize = lockScreenPaint.textSize,
                color = Color.WHITE,
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
        val DEFAULT_AOD_STROKE_WIDTH = "2dp"
        val TEXT_OUTLINE_DEFAULT_COLOR = Color.TRANSPARENT
        val AOD_DEFAULT_COLOR = Color.TRANSPARENT
        val AOD_OUTLINE_DEFAULT_COLOR = Color.WHITE
        private val DEFAULT_LIGHT_COLOR = "@android:color/system_accent1_100+0"
        private val DEFAULT_DARK_COLOR = "@android:color/system_accent2_600+0"

        fun getDefaultColor(assets: AssetLoader, isRegionDark: Boolean) =
            assets.readColor(if (isRegionDark) DEFAULT_LIGHT_COLOR else DEFAULT_DARK_COLOR)
    }
}
