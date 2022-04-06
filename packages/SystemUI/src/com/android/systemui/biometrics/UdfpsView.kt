/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import com.android.systemui.R
import com.android.systemui.doze.DozeReceiver

private const val TAG = "UdfpsView"

/**
 * The main view group containing all UDFPS animations.
 */
class UdfpsView(
    context: Context,
    attrs: AttributeSet?
) : FrameLayout(context, attrs), DozeReceiver, UdfpsIlluminator {

    private val sensorRect = RectF()
    private var hbmProvider: UdfpsHbmProvider? = null
    private val debugTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLUE
        textSize = 32f
    }

    private val sensorTouchAreaCoefficient: Float =
        context.theme.obtainStyledAttributes(attrs, R.styleable.UdfpsView, 0, 0).use { a ->
            require(a.hasValue(R.styleable.UdfpsView_sensorTouchAreaCoefficient)) {
                "UdfpsView must contain sensorTouchAreaCoefficient"
            }
            a.getFloat(R.styleable.UdfpsView_sensorTouchAreaCoefficient, 0f)
        }

    private val onIlluminatedDelayMs = context.resources.getInteger(
        com.android.internal.R.integer.config_udfps_illumination_transition_ms
    ).toLong()

    /** View controller (can be different for enrollment, BiometricPrompt, Keyguard, etc.). */
    var animationViewController: UdfpsAnimationViewController<*>? = null

    /** Properties used to obtain the sensor location. */
    var sensorProperties: FingerprintSensorPropertiesInternal? = null

    /** Debug message. */
    var debugMessage: String? = null
        set(value) {
            field = value
            postInvalidate()
        }

    /** When [startIllumination] has been called but not stopped via [stopIllumination]. */
    var isIlluminationRequested: Boolean = false
        private set

    override fun setHbmProvider(provider: UdfpsHbmProvider?) {
        hbmProvider = provider
    }

    // Don't propagate any touch events to the child views.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return (animationViewController == null || !animationViewController!!.shouldPauseAuth())
    }

    override fun dozeTimeTick() {
        animationViewController?.dozeTimeTick()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val paddingX = animationViewController?.paddingX ?: 0
        val paddingY = animationViewController?.paddingY ?: 0
        val sensorRadius = sensorProperties?.location?.sensorRadius ?: 0

        sensorRect.set(
            paddingX.toFloat(),
            paddingY.toFloat(),
            (2 * sensorRadius + paddingX).toFloat(),
            (2 * sensorRadius + paddingY).toFloat()
        )
        animationViewController?.onSensorRectUpdated(RectF(sensorRect))
    }

    fun onTouchOutsideView() {
        animationViewController?.onTouchOutsideView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.v(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.v(TAG, "onDetachedFromWindow")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isIlluminationRequested) {
            if (!debugMessage.isNullOrEmpty()) {
                canvas.drawText(debugMessage!!, 0f, 160f, debugTextPaint)
            }
        }
    }

    fun isWithinSensorArea(x: Float, y: Float): Boolean {
        // The X and Y coordinates of the sensor's center.
        val translation = animationViewController?.touchTranslation ?: PointF(0f, 0f)
        val cx = sensorRect.centerX() + translation.x
        val cy = sensorRect.centerY() + translation.y
        // Radii along the X and Y axes.
        val rx = (sensorRect.right - sensorRect.left) / 2.0f
        val ry = (sensorRect.bottom - sensorRect.top) / 2.0f

        return x > cx - rx * sensorTouchAreaCoefficient &&
            x < cx + rx * sensorTouchAreaCoefficient &&
            y > cy - ry * sensorTouchAreaCoefficient &&
            y < cy + ry * sensorTouchAreaCoefficient &&
            !(animationViewController?.shouldPauseAuth() ?: false)
    }

    /**
     * Start and run [onIlluminatedRunnable] when the first illumination frame reaches the panel.
     */
    override fun startIllumination(onIlluminatedRunnable: Runnable?) {
        isIlluminationRequested = true
        animationViewController?.onIlluminationStarting()
        doIlluminate(onIlluminatedRunnable)
    }

    private fun doIlluminate(onIlluminatedRunnable: Runnable?) {
        hbmProvider?.enableHbm() {
            if (onIlluminatedRunnable != null) {
                // No framework API can reliably tell when a frame reaches the panel. A timeout
                // is the safest solution.
                postDelayed(onIlluminatedRunnable, onIlluminatedDelayMs)
            } else {
                Log.w(TAG, "doIlluminate | onIlluminatedRunnable is null")
            }
        }
    }

    override fun stopIllumination() {
        isIlluminationRequested = false
        animationViewController?.onIlluminationStopped()
        hbmProvider?.disableHbm(null /* onHbmDisabled */)
    }
}
