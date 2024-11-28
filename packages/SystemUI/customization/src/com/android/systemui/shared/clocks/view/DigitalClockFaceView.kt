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
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.log.core.Logger
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockFontAxisSetting
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.shared.clocks.ClockContext
import com.android.systemui.shared.clocks.LogUtil
import java.util.Locale

// TODO(b/364680879): Merge w/ only subclass FlexClockView
abstract class DigitalClockFaceView(clockCtx: ClockContext) : FrameLayout(clockCtx.context) {
    protected val logger = Logger(clockCtx.messageBuffer, this::class.simpleName!!)
        get() = field ?: LogUtil.FALLBACK_INIT_LOGGER

    abstract var digitalClockTextViewMap: MutableMap<Int, SimpleDigitalClockTextView>

    @VisibleForTesting
    var isAnimationEnabled = true
        set(value) {
            field = value
            digitalClockTextViewMap.forEach { _, view -> view.isAnimationEnabled = value }
        }

    var dozeFraction: Float = 0F
        set(value) {
            field = value
            digitalClockTextViewMap.forEach { _, view -> view.dozeFraction = field }
        }

    val dozeControlState = DozeControlState()

    var isReactiveTouchInteractionEnabled = false
        set(value) {
            field = value
        }

    open val text: String?
        get() = null

    open fun refreshTime() = logger.d("refreshTime()")

    override fun invalidate() {
        logger.d("invalidate()")
        super.invalidate()
    }

    override fun requestLayout() {
        logger.d("requestLayout()")
        super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.d("onMeasure()")
        calculateSize(widthMeasureSpec, heightMeasureSpec)?.let { setMeasuredDimension(it.x, it.y) }
            ?: run { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        calculateLeftTopPosition()
        dozeControlState.animateReady = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        logger.d("onLayout()")
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        text?.let { logger.d({ "onDraw($str1)" }) { str1 = it } } ?: run { logger.d("onDraw()") }
        super.onDraw(canvas)
    }

    /*
     * Called in onMeasure to generate width/height overrides to the normal measuring logic. A null
     * result causes the normal view measuring logic to execute.
     */
    protected open fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point? = null

    protected open fun calculateLeftTopPosition() {}

    override fun addView(child: View?) {
        if (child == null) return
        logger.d({ "addView($str1 @$int1)" }) {
            str1 = child::class.simpleName!!
            int1 = child.id
        }
        super.addView(child)
        if (child is SimpleDigitalClockTextView) {
            digitalClockTextViewMap[child.id] = child
        }
        child.setWillNotDraw(true)
    }

    open fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        digitalClockTextViewMap.forEach { _, view -> view.animateDoze(isDozing, isAnimated) }
    }

    open fun animateCharge() {
        digitalClockTextViewMap.forEach { _, view -> view.animateCharge() }
    }

    open fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}

    fun updateColor(color: Int) {
        digitalClockTextViewMap.forEach { _, view -> view.updateColor(color) }
        invalidate()
    }

    fun updateAxes(axes: List<ClockFontAxisSetting>) {
        digitalClockTextViewMap.forEach { _, view -> view.updateAxes(axes) }
        requestLayout()
    }

    fun onFontSettingChanged(fontSizePx: Float) {
        digitalClockTextViewMap.forEach { _, view -> view.applyTextSize(fontSizePx) }
    }

    open val hasCustomWeatherDataDisplay
        get() = false

    open val hasCustomPositionUpdatedAnimation
        get() = false

    /** True if it's large weather clock, will use weatherBlueprint in compose */
    open val useCustomClockScene
        get() = false

    open fun onLocaleChanged(locale: Locale) {}

    open fun onWeatherDataChanged(data: WeatherData) {}

    open fun onAlarmDataChanged(data: AlarmData) {}

    open fun onZenDataChanged(data: ZenData) {}

    open fun onPickerCarouselSwiping(swipingFraction: Float) {}

    open fun isAlignedWithScreen(): Boolean = false

    /**
     * animateDoze needs correct translate value, which is calculated in onMeasure so we need to
     * delay this animation when we get correct values
     */
    class DozeControlState {
        var animateDoze: () -> Unit = {}
            set(value) {
                if (animateReady) {
                    value()
                    field = {}
                } else {
                    field = value
                }
            }

        var animateReady = false
            set(value) {
                if (value) {
                    animateDoze()
                    animateDoze = {}
                }
                field = value
            }
    }
}
