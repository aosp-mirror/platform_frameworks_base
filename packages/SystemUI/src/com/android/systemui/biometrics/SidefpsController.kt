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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityTaskManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.hardware.biometrics.BiometricOverlayConstants
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManager
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.util.Log
import android.view.View.AccessibilityDelegate
import android.view.accessibility.AccessibilityEvent
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.recents.OverviewProxyService
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
    private val activityTaskManager: ActivityTaskManager,
    overviewProxyService: OverviewProxyService,
    displayManager: DisplayManager,
    @Main mainExecutor: DelayableExecutor,
    @Main private val handler: Handler
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

    @VisibleForTesting
    val overviewProxyListener = object : OverviewProxyService.OverviewProxyListener {
        override fun onTaskbarStatusUpdated(visible: Boolean, stashed: Boolean) {
            overlayView?.let { view ->
                handler.postDelayed({ updateOverlayVisibility(view) }, 500)
            }
        }
    }

    private val animationDuration =
        context.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()

    private var overlayHideAnimator: ViewPropertyAnimator? = null

    private var overlayView: View? = null
        set(value) {
            field?.let { oldView ->
                windowManager.removeView(oldView)
                orientationListener.disable()
            }
            overlayHideAnimator?.cancel()
            overlayHideAnimator = null

            field = value
            field?.let { newView ->
                windowManager.addView(newView, overlayViewParams)
                updateOverlayVisibility(newView)
                orientationListener.enable()
            }
        }
    private var overlayOffsets: SensorLocationInternal = SensorLocationInternal.DEFAULT

    private val overlayViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS,
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
            override fun show(
                sensorId: Int,
                @BiometricOverlayConstants.ShowReason reason: Int
            ) = if (reason.isReasonToShow(activityTaskManager)) doShow() else hide(sensorId)

            private fun doShow() = mainExecutor.execute {
                if (overlayView == null) {
                    overlayView = createOverlayForDisplay()
                } else {
                    Log.v(TAG, "overlay already shown")
                }
            }

            override fun hide(sensorId: Int) = mainExecutor.execute { overlayView = null }
        })
        overviewProxyService.addCallback(overviewProxyListener)
    }

    private fun onOrientationChanged() {
        if (overlayView != null) {
            overlayView = createOverlayForDisplay()
        }
    }

    private fun createOverlayForDisplay(): View {
        val view = layoutInflater.inflate(R.layout.sidefps_view, null, false)
        val display = context.display!!

        val offsets = sensorProps.getLocation(display.uniqueId).let { location ->
            if (location == null) {
                Log.w(TAG, "No location specified for display: ${display.uniqueId}")
            }
            location ?: sensorProps.location
        }
        overlayOffsets = offsets

        val lottie = view.findViewById(R.id.sidefps_animation) as LottieAnimationView
        view.rotation = display.asSideFpsAnimationRotation(offsets.isYAligned())

        updateOverlayParams(display, lottie.composition?.bounds ?: Rect())
        lottie.setAnimation(display.asSideFpsAnimation(offsets.isYAligned()))
        lottie.addLottieOnCompositionLoadedListener {
            if (overlayView == view) {
                updateOverlayParams(display, it.bounds)
                windowManager.updateViewLayout(overlayView, overlayViewParams)
            }
        }
        lottie.addOverlayDynamicColor(context)

        /**
         * Intercepts TYPE_WINDOW_STATE_CHANGED accessibility event, preventing Talkback from
         * speaking @string/accessibility_fingerprint_label twice when sensor location indicator
         * is in focus
         */
        view.setAccessibilityDelegate(object : AccessibilityDelegate() {
            override fun dispatchPopulateAccessibilityEvent(
                host: View,
                event: AccessibilityEvent
            ): Boolean {
                return if (event.getEventType() === AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    true
                } else {
                    super.dispatchPopulateAccessibilityEvent(host, event)
                }
            }
        })
        return view
    }

    private fun updateOverlayParams(display: Display, bounds: Rect) {
        val isPortrait = display.isPortrait()
        val size = windowManager.maximumWindowMetrics.bounds
        val displayWidth = if (isPortrait) size.width() else size.height()
        val displayHeight = if (isPortrait) size.height() else size.width()

        // ignore sensorRadius since it's assumed that the sensor is on the side and centered at
        // either sensorLocationX or sensorLocationY (both should not be set)
        val (x, y) = if (overlayOffsets.isYAligned()) {
            when (display.rotation) {
                Surface.ROTATION_90 ->
                    Pair(overlayOffsets.sensorLocationY, 0)
                Surface.ROTATION_270 ->
                    Pair(
                        displayHeight - overlayOffsets.sensorLocationY - bounds.width(),
                        displayWidth + bounds.height()
                    )
                Surface.ROTATION_180 ->
                    Pair(0, displayHeight - overlayOffsets.sensorLocationY - bounds.height())
                else ->
                    Pair(displayWidth, overlayOffsets.sensorLocationY)
            }
        } else {
            when (display.rotation) {
                Surface.ROTATION_90 ->
                    Pair(0, displayWidth - overlayOffsets.sensorLocationX - bounds.height())
                Surface.ROTATION_270 ->
                    Pair(displayWidth, overlayOffsets.sensorLocationX - bounds.height())
                Surface.ROTATION_180 ->
                    Pair(
                        displayWidth - overlayOffsets.sensorLocationX - bounds.width(),
                        displayHeight
                    )
                else ->
                    Pair(overlayOffsets.sensorLocationX, 0)
            }
        }
        overlayViewParams.x = x
        overlayViewParams.y = y
    }

    private fun updateOverlayVisibility(view: View) {
        if (view != overlayView) {
            return
        }

        // hide after a few seconds if the sensor is oriented down and there are
        // large overlapping system bars
        val rotation = context.display?.rotation
        if (windowManager.currentWindowMetrics.windowInsets.hasBigNavigationBar() &&
            ((rotation == Surface.ROTATION_270 && overlayOffsets.isYAligned()) ||
                    (rotation == Surface.ROTATION_180 && !overlayOffsets.isYAligned()))) {
            overlayHideAnimator = view.animate()
                .alpha(0f)
                .setStartDelay(3_000)
                .setDuration(animationDuration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        overlayHideAnimator = null
                    }
                })
        } else {
            overlayHideAnimator?.cancel()
            overlayHideAnimator = null
            view.alpha = 1f
            view.visibility = View.VISIBLE
        }
    }
}

@BiometricOverlayConstants.ShowReason
private fun Int.isReasonToShow(activityTaskManager: ActivityTaskManager): Boolean = when (this) {
    REASON_AUTH_KEYGUARD -> false
    REASON_AUTH_SETTINGS -> when (activityTaskManager.topClass()) {
        // TODO(b/186176653): exclude fingerprint overlays from this list view
        "com.android.settings.biometrics.fingerprint.FingerprintSettings" -> false
        else -> true
    }
    else -> true
}

private fun ActivityTaskManager.topClass(): String =
    getTasks(1).firstOrNull()?.topActivity?.className ?: ""

@RawRes
private fun Display.asSideFpsAnimation(yAligned: Boolean): Int = when (rotation) {
    Surface.ROTATION_0 -> if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
    Surface.ROTATION_180 -> if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
    else -> if (yAligned) R.raw.sfps_pulse_landscape else R.raw.sfps_pulse
}

private fun Display.asSideFpsAnimationRotation(yAligned: Boolean): Float = when (rotation) {
    Surface.ROTATION_90 -> if (yAligned) 0f else 180f
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> if (yAligned) 180f else 0f
    else -> 0f
}

private fun SensorLocationInternal.isYAligned(): Boolean = sensorLocationY != 0

private fun Display.isPortrait(): Boolean =
    rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

private fun WindowInsets.hasBigNavigationBar(): Boolean =
    getInsets(WindowInsets.Type.navigationBars()).bottom >= 70

private fun LottieAnimationView.addOverlayDynamicColor(context: Context) {
    fun update() {
        val c = context.getColor(R.color.biometric_dialog_accent)
        for (key in listOf(".blue600", ".blue400")) {
            addValueCallback(
                KeyPath(key, "**"),
                LottieProperty.COLOR_FILTER
            ) { PorterDuffColorFilter(c, PorterDuff.Mode.SRC_ATOP) }
        }
    }

    if (composition != null) {
        update()
    } else {
        addLottieOnCompositionLoadedListener { update() }
    }
}
