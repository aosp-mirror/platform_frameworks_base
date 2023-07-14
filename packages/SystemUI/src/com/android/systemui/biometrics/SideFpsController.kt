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
import android.graphics.PixelFormat
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
import android.view.Display
import android.view.DisplayInfo
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
import com.airbnb.lottie.LottieAnimationView
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.ui.binder.SideFpsOverlayViewBinder
import com.android.systemui.biometrics.ui.viewmodel.SideFpsOverlayViewModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.traceSection
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider
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
    private val sideFpsOverlayViewModelFactory: Provider<SideFpsOverlayViewModel>,
    @Application private val scope: CoroutineScope,
    dumpManager: DumpManager
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
            BiometricOverlayConstants.REASON_UNKNOWN
        )

    @VisibleForTesting val orientationListener = orientationReasonListener.orientationListener

    private val isReverseDefaultRotation =
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)

    private var overlayHideAnimator: ViewPropertyAnimator? = null

    private var overlayView: View? = null
        set(value) {
            field?.let { oldView ->
                val lottie = oldView.findViewById(R.id.sidefps_animation) as LottieAnimationView
                lottie.pauseAnimation()
                windowManager.removeView(oldView)
                orientationListener.disable()
            }
            overlayHideAnimator?.cancel()
            overlayHideAnimator = null

            field = value
            field?.let { newView ->
                windowManager.addView(newView, overlayViewParams)
                orientationListener.enable()
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
        fingerprintManager?.setSidefpsController(
            object : ISidefpsController.Stub() {
                override fun show(
                    sensorId: Int,
                    @BiometricOverlayConstants.ShowReason reason: Int
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

    private fun listenForAlternateBouncerVisibility() {
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(true)
        scope.launch {
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
        @BiometricOverlayConstants.ShowReason reason: Int = BiometricOverlayConstants.REASON_UNKNOWN
    ) {
        if (!displayStateInteractor.isInRearDisplayMode.value) {
            requests.add(request)
            mainExecutor.execute {
                if (overlayView == null) {
                    traceSection(
                        "SideFpsController#show(request=${request.name}, reason=$reason)"
                    ) {
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
        requests.remove(request)
        mainExecutor.execute {
            if (requests.isEmpty()) {
                traceSection("SideFpsController#hide(${request.name})") { overlayView = null }
            }
        }
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

        pw.println("overlayOffsets=$overlayOffsets")
        pw.println("isReverseDefaultRotation=$isReverseDefaultRotation")
        pw.println("currentRotation=${displayInfo.rotation}")
    }

    private fun onOrientationChanged(@BiometricOverlayConstants.ShowReason reason: Int) {
        if (overlayView != null) {
            createOverlayForDisplay(reason)
        }
    }

    private fun createOverlayForDisplay(@BiometricOverlayConstants.ShowReason reason: Int) {
        val view = layoutInflater.inflate(R.layout.sidefps_view, null, false)
        overlayView = view
        SideFpsOverlayViewBinder.bind(
            view = view,
            viewModel = sideFpsOverlayViewModelFactory.get(),
            overlayViewParams = overlayViewParams,
            reason = reason,
            context = context,
        )
        orientationReasonListener.reason = reason
    }
}

private val FingerprintManager?.sideFpsSensorProperties: FingerprintSensorPropertiesInternal?
    get() = this?.sensorPropertiesInternal?.firstOrNull { it.isAnySidefpsType }

/** Returns [True] when the device has a side fingerprint sensor. */
fun FingerprintManager?.hasSideFpsSensor(): Boolean = this?.sideFpsSensorProperties != null

@BiometricOverlayConstants.ShowReason
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

private fun SensorLocationInternal.isYAligned(): Boolean = sensorLocationY != 0

private fun Display.isNaturalOrientation(): Boolean =
    rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

public class OrientationReasonListener(
    context: Context,
    displayManager: DisplayManager,
    handler: Handler,
    sensorProps: FingerprintSensorPropertiesInternal,
    onOrientationChanged: (reason: Int) -> Unit,
    @BiometricOverlayConstants.ShowReason var reason: Int
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
 * [BiometricOverlayConstants] which corrresponds with the reason fingerprint authentication is
 * requested.
 */
enum class SideFpsUiRequestSource {
    /** see [isReasonToAutoShow] */
    AUTO_SHOW,
    /** Pin, pattern or password bouncer */
    PRIMARY_BOUNCER,
    ALTERNATE_BOUNCER
}
