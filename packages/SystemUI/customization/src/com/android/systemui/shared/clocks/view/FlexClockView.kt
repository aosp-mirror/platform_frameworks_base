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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.android.app.animation.Interpolators
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.shared.clocks.AssetLoader
import com.android.systemui.shared.clocks.DigitTranslateAnimator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun clamp(value: Float, minVal: Float, maxVal: Float): Float = max(min(value, maxVal), minVal)

class FlexClockView(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    DigitalClockFaceView(context, messageBuffer) {
    override var digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    val digitLeftTopMap = mutableMapOf<Int, Point>()
    var maxSingleDigitSize = Point(-1, -1)
    val lockscreenTranslate = Point(0, 0)
    val aodTranslate = Point(0, 0)

    init {
        setWillNotDraw(false)
        layoutParams =
            RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
    }

    override fun addView(child: View?) {
        super.addView(child)
        (child as SimpleDigitalClockTextView).digitTranslateAnimator =
            DigitTranslateAnimator(::invalidate)
    }

    protected override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        maxSingleDigitSize = Point(-1, -1)
        digitalClockTextViewMap.forEach { (_, textView) ->
            textView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            maxSingleDigitSize.x = max(maxSingleDigitSize.x, textView.measuredWidth)
            maxSingleDigitSize.y = max(maxSingleDigitSize.y, textView.measuredHeight)
        }
        val textView = digitalClockTextViewMap[R.id.HOUR_FIRST_DIGIT]!!
        aodTranslate.x = -(maxSingleDigitSize.x * AOD_HORIZONTAL_TRANSLATE_RATIO).toInt()
        aodTranslate.y = (maxSingleDigitSize.y * AOD_VERTICAL_TRANSLATE_RATIO).toInt()
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
        digitLeftTopMap.forEach { _, point ->
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
        digitalClockTextViewMap.forEach { (id, _) ->
            val textView = digitalClockTextViewMap[id]!!
            canvas.translate(digitLeftTopMap[id]!!.x.toFloat(), digitLeftTopMap[id]!!.y.toFloat())
            textView.draw(canvas)
            canvas.translate(-digitLeftTopMap[id]!!.x.toFloat(), -digitLeftTopMap[id]!!.y.toFloat())
        }
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

    companion object {
        val AOD_TRANSITION_DURATION = 750L
        val CHARGING_TRANSITION_DURATION = 300L

        val AOD_HORIZONTAL_TRANSLATE_RATIO = 0.15F
        val AOD_VERTICAL_TRANSLATE_RATIO = 0.075F

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
