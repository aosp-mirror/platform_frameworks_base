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
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieAnimationView
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

private const val TAG = "SidefpsController"

/**
 * Shows and hides the side fingerprint sensor (side-fps) overlay and handles side fps touch events.
 */
@SysUISingleton
class SidefpsController @Inject constructor(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    fingerprintManager: FingerprintManager?,
    private val windowManager: WindowManager,
    @Main mainExecutor: DelayableExecutor,
    displayManager: DisplayManager,
    @Main handler: Handler
) {
    @VisibleForTesting
    val sensorProps: FingerprintSensorPropertiesInternal = fingerprintManager
        ?.sensorPropertiesInternal
        ?.firstOrNull { it.isAnySidefpsType }
        ?: throw IllegalStateException("no side fingerprint sensor")

    @VisibleForTesting
    val orientationListener = BiometricDisplayListener(
        context,
        displayManager,
        handler,
        BiometricDisplayListener.SensorType.SideFingerprint(sensorProps)
    ) { onOrientationChanged() }

    private var overlayView: View? = null
        set(value) {
            field?.let { oldView ->
                windowManager.removeView(oldView)
                orientationListener.disable()
            }
            field = value
            field?.let { newView ->
                windowManager.addView(newView, overlayViewParams)
                orientationListener.enable()
            }
        }

    private val overlayViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        title = TAG
        fitInsetsTypes = 0 // overrides default, avoiding status bars during layout
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
    }

    init {
        fingerprintManager?.setSidefpsController(object : ISidefpsController.Stub() {
            override fun show() = mainExecutor.execute {
                if (overlayView == null) {
                    overlayView = createOverlayForDisplay()
                } else {
                    Log.v(TAG, "overlay already shown")
                }
            }
            override fun hide() = mainExecutor.execute { overlayView = null }
        })
    }

    private fun onOrientationChanged() {
        if (overlayView != null) {
            overlayView = createOverlayForDisplay()
        }
    }

    private fun createOverlayForDisplay(): View {
        val view = layoutInflater.inflate(R.layout.sidefps_view, null, false)
        val display = context.display!!

        val lottie = view.findViewById(R.id.sidefps_animation) as LottieAnimationView
        lottie.setAnimation(display.asSideFpsAnimation())
        view.rotation = display.asSideFpsAnimationRotation()

        updateOverlayParams(display, lottie.composition?.bounds ?: Rect())
        lottie.addLottieOnCompositionLoadedListener {
            if (overlayView == view) {
                updateOverlayParams(display, it.bounds)
                windowManager.updateViewLayout(overlayView, overlayViewParams)
            }
        }

        return view
    }

    private fun updateOverlayParams(display: Display, bounds: Rect) {
        val isPortrait = display.isPortrait()
        val size = windowManager.maximumWindowMetrics.bounds
        val displayWidth = if (isPortrait) size.width() else size.height()
        val displayHeight = if (isPortrait) size.height() else size.width()
        val offsets = sensorProps.getLocation(display.uniqueId).let { location ->
            if (location == null) {
                Log.w(TAG, "No location specified for display: ${display.uniqueId}")
            }
            location ?: sensorProps.location
        }

        // ignore sensorLocationX and sensorRadius since it's assumed to be on the side
        // of the device and centered at sensorLocationY
        val (x, y) = when (display.rotation) {
            Surface.ROTATION_90 ->
                Pair(offsets.sensorLocationY, 0)
            Surface.ROTATION_270 ->
                Pair(displayHeight - offsets.sensorLocationY - bounds.width(), displayWidth)
            Surface.ROTATION_180 ->
                Pair(0, displayHeight - offsets.sensorLocationY - bounds.height())
            else ->
                Pair(displayWidth, offsets.sensorLocationY)
        }
        overlayViewParams.x = x
        overlayViewParams.y = y
    }
}

@RawRes
private fun Display.asSideFpsAnimation(): Int = when (rotation) {
    Surface.ROTATION_0 -> R.raw.sfps_pulse
    Surface.ROTATION_180 -> R.raw.sfps_pulse
    else -> R.raw.sfps_pulse_landscape
}

private fun Display.asSideFpsAnimationRotation(): Float = when (rotation) {
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> 180f
    else -> 0f
}

private fun Display.isPortrait(): Boolean =
    rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
