/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.biometrics.ui.viewmodel

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
import com.airbnb.lottie.model.KeyPath
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsSensorInteractor
import com.android.systemui.biometrics.domain.model.SideFpsSensorLocation
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.LottieCallback
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.DeviceEntrySideFpsOverlayInteractor
import com.android.systemui.keyguard.ui.viewmodel.SideFpsProgressBarViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Models UI of the side fingerprint sensor indicator view. */
class SideFpsOverlayViewModel
@Inject
constructor(
    @Application private val applicationContext: Context,
    biometricStatusInteractor: BiometricStatusInteractor,
    deviceEntrySideFpsOverlayInteractor: DeviceEntrySideFpsOverlayInteractor,
    displayStateInteractor: DisplayStateInteractor,
    sfpsSensorInteractor: SideFpsSensorInteractor,
    sideFpsProgressBarViewModel: SideFpsProgressBarViewModel
) {
    /** Contains properties of the side fingerprint sensor indicator */
    data class OverlayViewProperties(
        /** The raw asset for the indicator animation */
        val indicatorAsset: Int,
        /** Rotation of the overlayView */
        val overlayViewRotation: Float,
    )

    private val _lottieBounds: MutableStateFlow<Rect?> = MutableStateFlow(null)

    /** Used for setting lottie bounds once the composition has loaded. */
    fun setLottieBounds(bounds: Rect) {
        _lottieBounds.value = bounds
    }

    private val displayRotation = displayStateInteractor.currentRotation
    private val sensorLocation = sfpsSensorInteractor.sensorLocation

    /** Default LayoutParams for the overlayView */
    val defaultOverlayViewParams: WindowManager.LayoutParams
        get() =
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

    private val indicatorAsset: Flow<Int> =
        combine(displayRotation, sensorLocation) { rotation: DisplayRotation, sensorLocation ->
                val yAligned = sensorLocation.isSensorVerticalInDefaultOrientation
                val newAsset: Int =
                    when (rotation) {
                        DisplayRotation.ROTATION_0 ->
                            if (yAligned) {
                                R.raw.sfps_pulse
                            } else {
                                R.raw.sfps_pulse_landscape
                            }
                        DisplayRotation.ROTATION_180 ->
                            if (yAligned) {
                                R.raw.sfps_pulse
                            } else {
                                R.raw.sfps_pulse_landscape
                            }
                        else ->
                            if (yAligned) {
                                R.raw.sfps_pulse_landscape
                            } else {
                                R.raw.sfps_pulse
                            }
                    }
                newAsset
            }
            .distinctUntilChanged()

    private val overlayViewRotation: Flow<Float> =
        combine(
                displayRotation,
                sensorLocation,
            ) { rotation: DisplayRotation, sensorLocation ->
                val yAligned = sensorLocation.isSensorVerticalInDefaultOrientation
                when (rotation) {
                    DisplayRotation.ROTATION_90 -> if (yAligned) 0f else 180f
                    DisplayRotation.ROTATION_180 -> 180f
                    DisplayRotation.ROTATION_270 -> if (yAligned) 180f else 0f
                    else -> 0f
                }
            }
            .distinctUntilChanged()

    /** Contains properties (animation asset and view rotation) for overlayView */
    val overlayViewProperties: Flow<OverlayViewProperties> =
        combine(indicatorAsset, overlayViewRotation) { asset: Int, rotation: Float ->
            OverlayViewProperties(asset, rotation)
        }

    /** LayoutParams for placement of overlayView (the side fingerprint sensor indicator view) */
    val overlayViewParams: Flow<WindowManager.LayoutParams> =
        combine(
            _lottieBounds,
            sensorLocation,
            displayRotation,
        ) { bounds: Rect?, sensorLocation: SideFpsSensorLocation, displayRotation: DisplayRotation
            ->
            val topLeft = Point(sensorLocation.left, sensorLocation.top)

            if (sensorLocation.isSensorVerticalInDefaultOrientation) {
                if (displayRotation == DisplayRotation.ROTATION_0) {
                    topLeft.x -= bounds!!.width()
                } else if (displayRotation == DisplayRotation.ROTATION_270) {
                    topLeft.y -= bounds!!.height()
                }
            } else {
                if (displayRotation == DisplayRotation.ROTATION_180) {
                    topLeft.y -= bounds!!.height()
                } else if (displayRotation == DisplayRotation.ROTATION_270) {
                    topLeft.x -= bounds!!.width()
                }
            }
            defaultOverlayViewParams.apply {
                x = topLeft.x
                y = topLeft.y
            }
        }

    /** List of LottieCallbacks use for adding dynamic color to the overlayView */
    val lottieCallbacks: Flow<List<LottieCallback>> =
        combine(
            biometricStatusInteractor.sfpsAuthenticationReason,
            deviceEntrySideFpsOverlayInteractor.showIndicatorForDeviceEntry.distinctUntilChanged(),
            sideFpsProgressBarViewModel.isVisible,
        ) { reason: AuthenticationReason, showIndicatorForDeviceEntry: Boolean, progressBarIsVisible
            ->
            val callbacks = mutableListOf<LottieCallback>()
            if (showIndicatorForDeviceEntry) {
                val indicatorColor =
                    com.android.settingslib.Utils.getColorAttrDefaultColor(
                        applicationContext,
                        com.android.internal.R.attr.materialColorPrimaryFixed
                    )
                val outerRimColor =
                    com.android.settingslib.Utils.getColorAttrDefaultColor(
                        applicationContext,
                        com.android.internal.R.attr.materialColorPrimaryFixedDim
                    )
                val chevronFill =
                    com.android.settingslib.Utils.getColorAttrDefaultColor(
                        applicationContext,
                        com.android.internal.R.attr.materialColorOnPrimaryFixed
                    )
                callbacks.add(LottieCallback(KeyPath(".blue600", "**"), indicatorColor))
                callbacks.add(LottieCallback(KeyPath(".blue400", "**"), outerRimColor))
                callbacks.add(LottieCallback(KeyPath(".black", "**"), chevronFill))
            } else {
                if (!isDarkMode(applicationContext)) {
                    callbacks.add(LottieCallback(KeyPath(".black", "**"), Color.WHITE))
                }
                for (key in listOf(".blue600", ".blue400")) {
                    callbacks.add(
                        LottieCallback(
                            KeyPath(key, "**"),
                            applicationContext.getColor(
                                com.android.settingslib.color.R.color.settingslib_color_blue400
                            ),
                        )
                    )
                }
            }
            callbacks
        }

    companion object {
        private const val TAG = "SideFpsOverlayViewModel"
    }
}

private fun isDarkMode(context: Context): Boolean {
    val darkMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return darkMode == Configuration.UI_MODE_NIGHT_YES
}
