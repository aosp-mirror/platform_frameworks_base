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

package com.android.systemui.bouncer.log

import android.os.Build
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricSettingsInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricsAllowedInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor
import com.android.systemui.log.BouncerLogger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Startable that logs the flows that bouncer depends on. */
@OptIn(ExperimentalCoroutinesApi::class)
class BouncerLoggerStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    private val faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val fingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val bouncerLogger: BouncerLogger,
    private val deviceEntryBiometricsAllowedInteractor: DeviceEntryBiometricsAllowedInteractor,
) : CoreStartable {
    override fun start() {
        if (!Build.isDebuggable()) {
            return
        }
        applicationScope.launch {
            biometricSettingsInteractor.isFaceAuthEnrolledAndEnabled.collectLatest { newValue ->
                bouncerLogger.interestedStateChanged("isFaceAuthEnrolledAndEnabled", newValue)
            }
        }
        applicationScope.launch {
            biometricSettingsInteractor.isFingerprintAuthEnrolledAndEnabled.collectLatest { newValue
                ->
                bouncerLogger.interestedStateChanged(
                    "isFingerprintAuthEnrolledAndEnabled",
                    newValue
                )
            }
        }
        applicationScope.launch {
            faceAuthInteractor.isLockedOut.collectLatest { newValue ->
                bouncerLogger.interestedStateChanged("faceAuthLockedOut", newValue)
            }
        }
        applicationScope.launch {
            fingerprintAuthInteractor.isLockedOut.collectLatest { newValue ->
                bouncerLogger.interestedStateChanged("fingerprintLockedOut", newValue)
            }
        }
        applicationScope.launch {
            deviceEntryBiometricsAllowedInteractor.isFingerprintCurrentlyAllowedOnBouncer
                .collectLatest { newValue ->
                    bouncerLogger.interestedStateChanged(
                        "fingerprintCurrentlyAllowedOnBouncer",
                        newValue
                    )
                }
        }
    }
}
