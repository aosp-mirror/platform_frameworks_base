/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.INPUT_FEATURE_SPY
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.Execution
import java.util.*
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val TAG = "UdfpsOverlay"

// Number of sensor points needed inside ellipse for good overlap
private const val NEEDED_POINTS = 3

@SuppressLint("ClickableViewAccessibility")
@SysUISingleton
class UdfpsOverlay
@Inject
constructor(
    private val context: Context,
    private val execution: Execution,
    private val windowManager: WindowManager,
    private val fingerprintManager: FingerprintManager?,
    private val handler: Handler,
    private val biometricExecutor: Executor,
    private val alternateTouchProvider: Optional<AlternateUdfpsTouchProvider>,
    private val fgExecutor: DelayableExecutor,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val authController: AuthController,
    private val udfpsLogger: UdfpsLogger,
    private var featureFlags: FeatureFlags
) : CoreStartable {

    /** The view, when [isShowing], or null. */
    var overlayView: UdfpsOverlayView? = null
        private set

    private var requestId: Long = 0
    private var onFingerDown = false
    val size = windowManager.maximumWindowMetrics.bounds
    val udfpsProps: MutableList<FingerprintSensorPropertiesInternal> = mutableListOf()
    var points: Array<Point> = emptyArray()
    var processedMotionEvent = false

    private var params: UdfpsOverlayParams = UdfpsOverlayParams()

    private val coreLayoutParams =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                0 /* flags set in computeLayoutParams() */,
                PixelFormat.TRANSLUCENT
            )
            .apply {
                title = TAG
                fitInsetsTypes = 0
                gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS
                privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                // Avoid announcing window title.
                accessibilityTitle = " "
                inputFeatures = INPUT_FEATURE_SPY
            }

    fun onTouch(v: View, event: MotionEvent): Boolean {
        val view = v as UdfpsOverlayView

        return when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                onFingerDown = true
                if (!view.isDisplayConfigured && alternateTouchProvider.isPresent) {
                    view.processMotionEvent(event)

                    val goodOverlap =
                        if (featureFlags.isEnabled(Flags.NEW_ELLIPSE_DETECTION)) {
                            isGoodEllipseOverlap(event)
                        } else {
                            isGoodCentroidOverlap(event)
                        }

                    if (!processedMotionEvent && goodOverlap) {
                        biometricExecutor.execute {
                            alternateTouchProvider
                                .get()
                                .onPointerDown(
                                    requestId,
                                    event.rawX.toInt(),
                                    event.rawY.toInt(),
                                    event.touchMinor,
                                    event.touchMajor
                                )
                        }
                        fgExecutor.execute {
                            if (keyguardUpdateMonitor.isFingerprintDetectionRunning) {
                                keyguardUpdateMonitor.onUdfpsPointerDown(requestId.toInt())
                            }
                        }

                        view.configureDisplay {
                            biometricExecutor.execute { alternateTouchProvider.get().onUiReady() }
                        }

                        processedMotionEvent = true
                    }
                }

                view.invalidate()
                true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (processedMotionEvent && alternateTouchProvider.isPresent) {
                    biometricExecutor.execute {
                        alternateTouchProvider.get().onPointerUp(requestId)
                    }
                    fgExecutor.execute {
                        if (keyguardUpdateMonitor.isFingerprintDetectionRunning) {
                            keyguardUpdateMonitor.onUdfpsPointerUp(requestId.toInt())
                        }
                    }

                    processedMotionEvent = false
                }

                if (view.isDisplayConfigured) {
                    view.unconfigureDisplay()
                }

                view.invalidate()
                true
            }
            else -> false
        }
    }

    fun isGoodEllipseOverlap(event: MotionEvent): Boolean {
        return points.count { checkPoint(event, it) } >= NEEDED_POINTS
    }

    fun isGoodCentroidOverlap(event: MotionEvent): Boolean {
        return params.sensorBounds.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    fun checkPoint(event: MotionEvent, point: Point): Boolean {
        // Calculate if sensor point is within ellipse
        // Formula: ((cos(o)(xE - xS) + sin(o)(yE - yS))^2 / a^2) + ((sin(o)(xE - xS) + cos(0)(yE -
        // yS))^2 / b^2) <= 1
        val a: Float = cos(event.orientation) * (point.x - event.rawX)
        val b: Float = sin(event.orientation) * (point.y - event.rawY)
        val c: Float = sin(event.orientation) * (point.x - event.rawX)
        val d: Float = cos(event.orientation) * (point.y - event.rawY)
        val result =
            (a + b).pow(2) / (event.touchMinor / 2).pow(2) +
                (c - d).pow(2) / (event.touchMajor / 2).pow(2)

        return result <= 1
    }

    fun show(requestId: Long): Boolean {
        this.requestId = requestId
        if (overlayView == null && alternateTouchProvider.isPresent) {
            UdfpsOverlayView(context, null).let {
                it.overlayParams = params
                it.setUdfpsDisplayMode(
                    UdfpsDisplayMode(context, execution, authController, udfpsLogger)
                )
                it.setOnTouchListener { v, event -> onTouch(v, event) }
                it.sensorPoints = points
                overlayView = it
            }
            windowManager.addView(overlayView, coreLayoutParams)
            return true
        }

        return false
    }

    fun hide() {
        overlayView?.apply {
            windowManager.removeView(this)
            setOnTouchListener(null)
        }

        overlayView = null
    }

    @Override
    override fun start() {
        fingerprintManager?.addAuthenticatorsRegisteredCallback(
            object : IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                override fun onAllAuthenticatorsRegistered(
                    sensors: List<FingerprintSensorPropertiesInternal>
                ) {
                    handler.post { handleAllFingerprintAuthenticatorsRegistered(sensors) }
                }
            }
        )
    }

    private fun handleAllFingerprintAuthenticatorsRegistered(
        sensors: List<FingerprintSensorPropertiesInternal>
    ) {
        for (props in sensors) {
            if (props.isAnyUdfpsType) {
                udfpsProps.add(props)
            }
        }

        // Setup param size
        if (udfpsProps.isNotEmpty()) {
            params =
                UdfpsOverlayParams(
                    sensorBounds = udfpsProps[0].location.rect,
                    overlayBounds = Rect(0, size.height() / 2, size.width(), size.height()),
                    naturalDisplayWidth = size.width(),
                    naturalDisplayHeight = size.height(),
                    scaleFactor = 1f
                )

            val sensorX = params.sensorBounds.centerX()
            val sensorY = params.sensorBounds.centerY()
            val gridOffset: Int = params.sensorBounds.width() / 3

            points =
                arrayOf(
                    Point(sensorX - gridOffset, sensorY - gridOffset),
                    Point(sensorX, sensorY - gridOffset),
                    Point(sensorX + gridOffset, sensorY - gridOffset),
                    Point(sensorX - gridOffset, sensorY),
                    Point(sensorX, sensorY),
                    Point(sensorX + gridOffset, sensorY),
                    Point(sensorX - gridOffset, sensorY + gridOffset),
                    Point(sensorX, sensorY + gridOffset),
                    Point(sensorX + gridOffset, sensorY + gridOffset)
                )
        }
    }
}
