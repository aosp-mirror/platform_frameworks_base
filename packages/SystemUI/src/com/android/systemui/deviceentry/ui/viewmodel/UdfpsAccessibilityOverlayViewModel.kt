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
 */

package com.android.systemui.deviceentry.ui.viewmodel

import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.biometrics.UdfpsUtils
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Models the UI state for the UDFPS accessibility overlay */
@ExperimentalCoroutinesApi
class UdfpsAccessibilityOverlayViewModel
@Inject
constructor(
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    accessibilityInteractor: AccessibilityInteractor,
    deviceEntryIconViewModel: DeviceEntryIconViewModel,
    deviceEntryFgIconViewModel: DeviceEntryForegroundViewModel,
) {
    private val udfpsUtils = UdfpsUtils()
    private val udfpsOverlayParams: StateFlow<UdfpsOverlayParams> =
        udfpsOverlayInteractor.udfpsOverlayParams

    /** Overlay is only visible if touch exploration is enabled and UDFPS can be used. */
    val visible: Flow<Boolean> =
        accessibilityInteractor.isTouchExplorationEnabled.flatMapLatest { touchExplorationEnabled ->
            if (touchExplorationEnabled) {
                combine(
                    deviceEntryFgIconViewModel.viewModel,
                    deviceEntryIconViewModel.deviceEntryViewAlpha,
                ) { iconViewModel, alpha ->
                    iconViewModel.type == DeviceEntryIconView.IconType.FINGERPRINT &&
                        !iconViewModel.useAodVariant &&
                        alpha == 1f
                }
            } else {
                flowOf(false)
            }
        }

    /** Give directional feedback to help the user authenticate with UDFPS. */
    fun onHoverEvent(v: View, event: MotionEvent): Boolean {
        val overlayParams = udfpsOverlayParams.value
        val scaledTouch: Point =
            udfpsUtils.getTouchInNativeCoordinates(event.getPointerId(0), event, overlayParams)

        if (!udfpsUtils.isWithinSensorArea(event.getPointerId(0), event, overlayParams)) {
            // view only receives motionEvents when [visible] which requires touchExplorationEnabled
            val announceStr =
                udfpsUtils.onTouchOutsideOfSensorArea(
                    /* touchExplorationEnabled */ true,
                    v.context,
                    scaledTouch.x,
                    scaledTouch.y,
                    overlayParams,
                )
            if (announceStr != null) {
                v.announceForAccessibility(announceStr)
            }
        }
        // always let the motion events go through to underlying views
        return false
    }
}
