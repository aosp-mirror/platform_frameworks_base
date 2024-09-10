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

package com.android.systemui.biometrics.domain.interactor

import android.util.Log
import com.android.systemui.biometrics.shared.model.AuthenticationReason.NotRunning
import com.android.systemui.keyguard.domain.interactor.DeviceEntrySideFpsOverlayInteractor
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

/** Encapsulates business logic for showing and hiding the side fingerprint sensor indicator. */
interface SideFpsOverlayInteractor {
    /** Whether the side fingerprint sensor indicator is currently showing. */
    val isShowing: Flow<Boolean>
}

@OptIn(ExperimentalCoroutinesApi::class)
class SideFpsOverlayInteractorImpl
@Inject
constructor(
    biometricStatusInteractor: BiometricStatusInteractor,
    displayStateInteractor: DisplayStateInteractor,
    deviceEntrySideFpsOverlayInteractor: DeviceEntrySideFpsOverlayInteractor,
    sfpsSensorInteractor: SideFpsSensorInteractor,
    // TODO(b/365182034): add progress bar input when rest to unlock feature is implemented
) : SideFpsOverlayInteractor {
    private val sfpsOverlayEnabled: Flow<Boolean> =
        sfpsSensorInteractor.isAvailable.sample(displayStateInteractor.isInRearDisplayMode) {
            isAvailable: Boolean,
            isInRearDisplayMode: Boolean ->
            isAvailable && !isInRearDisplayMode
        }

    private val showSideFpsOverlay: Flow<Boolean> =
        combine(
            biometricStatusInteractor.sfpsAuthenticationReason,
            deviceEntrySideFpsOverlayInteractor.showIndicatorForDeviceEntry,
            // TODO(b/365182034): add progress bar input when rest to unlock feature is implemented
        ) { systemServerAuthReason, showIndicatorForDeviceEntry ->
            Log.d(
                TAG,
                "systemServerAuthReason = $systemServerAuthReason, " +
                    "showIndicatorForDeviceEntry = $showIndicatorForDeviceEntry, "
            )
            systemServerAuthReason != NotRunning || showIndicatorForDeviceEntry
        }

    override val isShowing: Flow<Boolean> =
        sfpsOverlayEnabled
            .flatMapLatest { sfpsOverlayEnabled ->
                if (!sfpsOverlayEnabled) {
                    flowOf(false)
                } else {
                    showSideFpsOverlay
                }
            }
            .onEach { Log.d(TAG, "isShowing: $it") }

    companion object {
        private const val TAG = "SideFpsOverlayInteractor"
    }
}
