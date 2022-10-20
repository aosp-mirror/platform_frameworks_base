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
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.Execution
import java.util.*
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "UdfpsOverlay"

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
    private val udfpsLogger: UdfpsLogger
) : CoreStartable {

    /** The view, when [isShowing], or null. */
    var overlayView: UdfpsOverlayView? = null
        private set

    private var requestId: Long = 0
    private var onFingerDown = false
    val size = windowManager.maximumWindowMetrics.bounds
    val udfpsProps: MutableList<FingerprintSensorPropertiesInternal> = mutableListOf()

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
                    biometricExecutor.execute {
                        alternateTouchProvider
                            .get()
                            .onPointerDown(
                                requestId,
                                event.x.toInt(),
                                event.y.toInt(),
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
                }

                true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (onFingerDown && alternateTouchProvider.isPresent) {
                    biometricExecutor.execute {
                        alternateTouchProvider.get().onPointerUp(requestId)
                    }
                    fgExecutor.execute {
                        if (keyguardUpdateMonitor.isFingerprintDetectionRunning) {
                            keyguardUpdateMonitor.onUdfpsPointerUp(requestId.toInt())
                        }
                    }
                }
                onFingerDown = false
                if (view.isDisplayConfigured) {
                    view.unconfigureDisplay()
                }

                true
            }
            else -> false
        }
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
        }
    }
}
