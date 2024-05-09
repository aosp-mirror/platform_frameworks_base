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

import android.app.ActivityTaskManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.hardware.biometrics.BiometricRequestConstants
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManager
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.hardware.fingerprint.ISidefpsController
import android.os.Handler
import android.util.Log
import android.util.RotationUtils
import android.view.Display
import android.view.DisplayInfo
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.View.AccessibilityDelegate
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RawRes
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.android.app.animation.Interpolators
import com.android.app.tracing.traceSection
import com.android.internal.annotations.VisibleForTesting
import com.android.keyguard.KeyguardPINView
import com.android.systemui.Dumpable
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.shared.SideFpsControllerRefactor
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "SideFpsController"

/**
 * Shows and hides the side fingerprint sensor (side-fps) overlay and handles side fps touch events.
 */
@SysUISingleton
class SideFpsController
@Inject
constructor(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    fingerprintManager: FingerprintManager?,
    private val windowManager: WindowManager,
    private val activityTaskManager: ActivityTaskManager,
    displayManager: DisplayManager,
    private val displayStateInteractor: DisplayStateInteractor,
    @Main private val mainExecutor: DelayableExecutor,
    @Main private val handler: Handler,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    @Application private val applicationScope: CoroutineScope,
    dumpManager: DumpManager,
    fpsUnlockTracker: FpsUnlockTracker
) : Dumpable {
    private val requests: HashSet<SideFpsUiRequestSource> = HashSet()

    @VisibleForTesting
    val sensorProps: FingerprintSensorPropertiesInternal =
        fingerprintManager?.sideFpsSensorProperties
            ?: throw IllegalStateException("no side fingerprint sensor")

    @VisibleForTesting
    val orientationReasonListener =
        OrientationReasonListener(
            context,
            displayManager,
            handler,
            sensorProps,
            { reason -> onOrientationChanged(reason) },
            BiometricRequestConstants.REASON_UNKNOWN
        )

    @VisibleForTesting val orientationListener = orientationReasonListener.orientationListener

    private val isReverseDefaultRotation =
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)

    private var overlayShowAnimator: ViewPropertyAnimator? = null

    private var overlayView: View? = null
        set(value) {
            field?.let { oldView ->
                val lottie = oldView.requireViewById(R.id.sidefps_animation) as LottieAnimationView
                lottie.pauseAnimation()
                lottie.removeAllLottieOnCompositionLoadedListener()
                windowManager.removeView(oldView)
                orientationListener.disable()
            }
            overlayShowAnimator?.cancel()
            overlayShowAnimator = null

            field = value
            field?.let { newView ->
                if (requests.contains(SideFpsUiRequestSource.PRIMARY_BOUNCER)) {
                    newView.alpha = 0f
                    overlayShowAnimator =
                        newView
                            .animate()
                            .alpha(1f)
                            .setDuration(KeyguardPINView.ANIMATION_DURATION)
                            .setInterpolator(Interpolators.ALPHA_IN)
                }
                windowManager.addView(newView, overlayViewParams)
                orientationListener.enable()
                overlayShowAnimator?.start()
            }
        }
    @VisibleForTesting var overlayOffsets: SensorLocationInternal = SensorLocationInternal.DEFAULT

    private val displayInfo = DisplayInfo()

    private val overlayViewParams =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS,
                PixelFormat.TRANSLUCENT
            )
            .apply {
                title = TAG
                fitInsetsTypes = 0 // overrides default, avoiding status bars during layout
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY or PRIVATE_FLAG_NO_MOVE_ANIMATION
            }

    init {
        if (!SideFpsControllerRefactor.isEnabled) {
            fpsUnlockTracker.startTracking()
            fingerprintManager?.setSidefpsController(
                object : ISidefpsController.Stub() {
                    override fun show(
                        sensorId: Int,
                        @BiometricRequestConstants.RequestReason reason: Int
                    ) =
                        if (reason.isReasonToAutoShow(activityTaskManager)) {
                            show(SideFpsUiRequestSource.AUTO_SHOW, reason)
                        } else {
                            hide(SideFpsUiRequestSource.AUTO_SHOW)
                        }

                    override fun hide(sensorId: Int) = hide(SideFpsUiRequestSource.AUTO_SHOW)
                }
            )
            listenForAlternateBouncerVisibility()

            dumpManager.registerDumpable(this)
        }
    }

    private fun listenForAlternateBouncerVisibility() {
        if (!DeviceEntryUdfpsRefactor.isEnabled) {
            alternateBouncerInteractor.setAlternateBouncerUIAvailable(true, "SideFpsController")
        }

        applicationScope.launch {
            alternateBouncerInteractor.isVisible.collect { isVisible: Boolean ->
                if (isVisible) {
                    show(SideFpsUiRequestSource.ALTERNATE_BOUNCER, REASON_AUTH_KEYGUARD)
                } else {
                    hide(SideFpsUiRequestSource.ALTERNATE_BOUNCER)
                }
            }
        }
    }

    /** Shows the side fps overlay if not already shown. */
    fun show(
        request: SideFpsUiRequestSource,
        @BiometricRequestConstants.RequestReason
        reason: Int = BiometricRequestConstants.REASON_UNKNOWN
    ) {
        SideFpsControllerRefactor.assertInLegacyMode()
        if (!displayStateInteractor.isInRearDisplayMode.value) {
            mainExecutor.execute {
                if (overlayView == null) {
                    traceSection(
                        "SideFpsController#show(request=${request.name}, reason=$reason)"
                    ) {
                        requests.add(request)
                        createOverlayForDisplay(reason)
                    }
                } else {
                    Log.v(TAG, "overlay already shown")
                }
            }
        }
    }

    /** Hides the fps overlay if shown. */
    fun hide(request: SideFpsUiRequestSource) {
        SideFpsControllerRefactor.assertInLegacyMode()
        requests.remove(request)
        mainExecutor.execute {
            if (requests.isEmpty()) {
                traceSection("SideFpsController#hide(${request.name})") { overlayView = null }
            }
        }
    }

    /** Hide the arrow indicator. */
    fun hideIndicator() {
        SideFpsControllerRefactor.assertInLegacyMode()
        val lottieAnimationView =
            overlayView?.findViewById(R.id.sidefps_animation) as LottieAnimationView?
        lottieAnimationView?.visibility = INVISIBLE
    }

    /** Show the arrow indicator. */
    fun showIndicator() {
        SideFpsControllerRefactor.assertInLegacyMode()
        val lottieAnimationView =
            overlayView?.findViewById(R.id.sidefps_animation) as LottieAnimationView?
        lottieAnimationView?.visibility = VISIBLE
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("requests:")
        for (requestSource in requests) {
            pw.println("     $requestSource.name")
        }

        pw.println("overlayView:")
        pw.println("     width=${overlayView?.width}")
        pw.println("     height=${overlayView?.height}")
        pw.println("     boundsOnScreen=${overlayView?.boundsOnScreen}")

        pw.println("displayStateInteractor:")
        pw.println("     isInRearDisplayMode=${displayStateInteractor?.isInRearDisplayMode?.value}")

        pw.println("sensorProps:")
        pw.println("     displayId=${displayInfo.uniqueId}")
        pw.println("     sensorType=${sensorProps?.sensorType}")
        pw.println("     location=${sensorProps?.getLocation(displayInfo.uniqueId)}")
        pw.println("lottieAnimationView:")
        pw.println(
            "     visibility=${overlayView?.findViewById<View>(R.id.sidefps_animation)?.visibility}"
        )

        pw.println("overlayOffsets=$overlayOffsets")
        pw.println("isReverseDefaultRotation=$isReverseDefaultRotation")
        pw.println("currentRotation=${displayInfo.rotation}")
    }

    private fun onOrientationChanged(@BiometricRequestConstants.RequestReason reason: Int) {
        if (overlayView?.isAttachedToWindow == true) {
            createOverlayForDisplay(reason)
        }
    }

    private fun createOverlayForDisplay(@BiometricRequestConstants.RequestReason reason: Int) {
        val view = layoutInflater.inflate(R.layout.sidefps_view, null, false)
        overlayView = view
        val display = context.display!!
        // b/284098873 `context.display.rotation` may not up-to-date, we use displayInfo.rotation
        display.getDisplayInfo(displayInfo)
        val offsets =
            sensorProps.getLocation(display.uniqueId).let { location ->
                if (location == null) {
                    Log.w(TAG, "No location specified for display: ${display.uniqueId}")
                }
                location ?: sensorProps.location
            }
        overlayOffsets = offsets

        val lottie = view.requireViewById(R.id.sidefps_animation) as LottieAnimationView
        view.rotation =
            display.asSideFpsAnimationRotation(
                offsets.isYAligned(),
                getRotationFromDefault(displayInfo.rotation)
            )
        lottie.setAnimation(
            display.asSideFpsAnimation(
                offsets.isYAligned(),
                getRotationFromDefault(displayInfo.rotation)
            )
        )
        lottie.addLottieOnCompositionLoadedListener {
            // Check that view is not stale, and that overlayView has not been hidden/removed
            if (overlayView != null && overlayView == view) {
                updateOverlayParams(display, it.bounds)
            }
        }
        orientationReasonListener.reason = reason
        lottie.addOverlayDynamicColor(context, reason)

        /**
         * Intercepts TYPE_WINDOW_STATE_CHANGED accessibility event, preventing Talkback from
         * speaking @string/accessibility_fingerprint_label twice when sensor location indicator is
         * in focus
         */
        view.setAccessibilityDelegate(
            object : AccessibilityDelegate() {
                override fun dispatchPopulateAccessibilityEvent(
                    host: View,
                    event: AccessibilityEvent
                ): Boolean {
                    return if (
                        event.getEventType() === AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    ) {
                        true
                    } else {
                        super.dispatchPopulateAccessibilityEvent(host, event)
                    }
                }
            }
        )
    }

    @VisibleForTesting
    fun updateOverlayParams(display: Display, bounds: Rect) {
        val isNaturalOrientation = display.isNaturalOrientation()
        val isDefaultOrientation =
            if (isReverseDefaultRotation) !isNaturalOrientation else isNaturalOrientation
        val size = windowManager.maximumWindowMetrics.bounds

        val displayWidth = if (isDefaultOrientation) size.width() else size.height()
        val displayHeight = if (isDefaultOrientation) size.height() else size.width()
        val boundsWidth = if (isDefaultOrientation) bounds.width() else bounds.height()
        val boundsHeight = if (isDefaultOrientation) bounds.height() else bounds.width()

        val sensorBounds =
            if (overlayOffsets.isYAligned()) {
                Rect(
                    displayWidth - boundsWidth,
                    overlayOffsets.sensorLocationY,
                    displayWidth,
                    overlayOffsets.sensorLocationY + boundsHeight
                )
            } else {
                Rect(
                    overlayOffsets.sensorLocationX,
                    0,
                    overlayOffsets.sensorLocationX + boundsWidth,
                    boundsHeight
                )
            }

        RotationUtils.rotateBounds(
            sensorBounds,
            Rect(0, 0, displayWidth, displayHeight),
            getRotationFromDefault(display.rotation)
        )

        overlayViewParams.x = sensorBounds.left
        overlayViewParams.y = sensorBounds.top

        windowManager.updateViewLayout(overlayView, overlayViewParams)
    }

    private fun getRotationFromDefault(rotation: Int): Int =
        if (isReverseDefaultRotation) (rotation + 1) % 4 else rotation
}

private val FingerprintManager?.sideFpsSensorProperties: FingerprintSensorPropertiesInternal?
    get() = this?.sensorPropertiesInternal?.firstOrNull { it.isAnySidefpsType }

/** Returns [True] when the device has a side fingerprint sensor. */
fun FingerprintManager?.hasSideFpsSensor(): Boolean = this?.sideFpsSensorProperties != null

@BiometricRequestConstants.RequestReason
private fun Int.isReasonToAutoShow(activityTaskManager: ActivityTaskManager): Boolean =
    when (this) {
        REASON_AUTH_KEYGUARD -> false
        REASON_AUTH_SETTINGS ->
            when (activityTaskManager.topClass()) {
                // TODO(b/186176653): exclude fingerprint overlays from this list view
                "com.android.settings.biometrics.fingerprint.FingerprintSettings" -> false
                else -> true
            }
        else -> true
    }

private fun ActivityTaskManager.topClass(): String =
    getTasks(1).firstOrNull()?.topActivity?.className ?: ""

@RawRes
private fun Display.asSideFpsAnimation(yAligned: Boolean, rotationFromDefault: Int): Int =
    when (rotationFromDefault) {
        Surface.ROTATION_0 -> if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
        Surface.ROTATION_180 -> if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
        else -> if (yAligned) R.raw.sfps_pulse_landscape else R.raw.sfps_pulse
    }

private fun Display.asSideFpsAnimationRotation(yAligned: Boolean, rotationFromDefault: Int): Float =
    when (rotationFromDefault) {
        Surface.ROTATION_90 -> if (yAligned) 0f else 180f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> if (yAligned) 180f else 0f
        else -> 0f
    }

private fun SensorLocationInternal.isYAligned(): Boolean = sensorLocationY != 0

private fun Display.isNaturalOrientation(): Boolean =
    rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

private fun LottieAnimationView.addOverlayDynamicColor(
    context: Context,
    @BiometricRequestConstants.RequestReason reason: Int
) {
    fun update() {
        val isKeyguard = reason == REASON_AUTH_KEYGUARD
        if (isKeyguard) {
            val color =
                com.android.settingslib.Utils.getColorAttrDefaultColor(
                    context,
                    com.android.internal.R.attr.materialColorPrimaryFixed
                )
            val outerRimColor =
                com.android.settingslib.Utils.getColorAttrDefaultColor(
                    context,
                    com.android.internal.R.attr.materialColorPrimaryFixedDim
                )
            val chevronFill =
                com.android.settingslib.Utils.getColorAttrDefaultColor(
                    context,
                    com.android.internal.R.attr.materialColorOnPrimaryFixed
                )
            addValueCallback(KeyPath(".blue600", "**"), LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            }
            addValueCallback(KeyPath(".blue400", "**"), LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(outerRimColor, PorterDuff.Mode.SRC_ATOP)
            }
            addValueCallback(KeyPath(".black", "**"), LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(chevronFill, PorterDuff.Mode.SRC_ATOP)
            }
        } else {
            if (!isDarkMode(context)) {
                addValueCallback(KeyPath(".black", "**"), LottieProperty.COLOR_FILTER) {
                    PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                }
            }
            for (key in listOf(".blue600", ".blue400")) {
                addValueCallback(KeyPath(key, "**"), LottieProperty.COLOR_FILTER) {
                    PorterDuffColorFilter(
                        context.getColor(
                            com.android.settingslib.color.R.color.settingslib_color_blue400
                        ),
                        PorterDuff.Mode.SRC_ATOP
                    )
                }
            }
        }
    }

    if (composition != null) {
        update()
    } else {
        addLottieOnCompositionLoadedListener { update() }
    }
}

private fun isDarkMode(context: Context): Boolean {
    val darkMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return darkMode == Configuration.UI_MODE_NIGHT_YES
}

@VisibleForTesting
class OrientationReasonListener(
    context: Context,
    displayManager: DisplayManager,
    handler: Handler,
    sensorProps: FingerprintSensorPropertiesInternal,
    onOrientationChanged: (reason: Int) -> Unit,
    @BiometricRequestConstants.RequestReason var reason: Int
) {
    val orientationListener =
        BiometricDisplayListener(
            context,
            displayManager,
            handler,
            BiometricDisplayListener.SensorType.SideFingerprint(sensorProps)
        ) {
            onOrientationChanged(reason)
        }
}

/**
 * The source of a request to show the side fps visual indicator. This is distinct from
 * [BiometricRequestConstants] which corresponds with the reason fingerprint authentication is
 * requested.
 */
enum class SideFpsUiRequestSource {
    /** see [isReasonToAutoShow] */
    AUTO_SHOW,
    /** Pin, pattern or password bouncer */
    PRIMARY_BOUNCER,
    ALTERNATE_BOUNCER,
}
