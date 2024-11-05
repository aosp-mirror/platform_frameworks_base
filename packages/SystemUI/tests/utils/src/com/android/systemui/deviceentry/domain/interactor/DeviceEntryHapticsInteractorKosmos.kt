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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.deviceentry.domain.interactor

import com.android.keyguard.logging.biometricUnlockLogger
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyevent.domain.interactor.keyEventInteractor
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.util.time.systemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.deviceEntryHapticsInteractor by
    Kosmos.Fixture {
        DeviceEntryHapticsInteractor(
            biometricSettingsRepository = biometricSettingsRepository,
            deviceEntryBiometricAuthInteractor = deviceEntryBiometricAuthInteractor,
            deviceEntryFingerprintAuthInteractor = deviceEntryFingerprintAuthInteractor,
            deviceEntrySourceInteractor = deviceEntrySourceInteractor,
            fingerprintPropertyRepository = fingerprintPropertyRepository,
            keyEventInteractor = keyEventInteractor,
            logger = biometricUnlockLogger,
            powerInteractor = powerInteractor,
            systemClock = systemClock,
            dumpManager = dumpManager,
        )
    }
