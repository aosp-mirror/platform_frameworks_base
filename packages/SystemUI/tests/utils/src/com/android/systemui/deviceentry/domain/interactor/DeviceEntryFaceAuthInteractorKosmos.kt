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

import android.content.applicationContext
import com.android.keyguard.keyguardUpdateMonitor
import com.android.keyguard.trustManager
import com.android.systemui.biometrics.data.repository.facePropertyRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.deviceentry.data.repository.faceWakeUpTriggersConfig
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.faceAuthLogger by Kosmos.Fixture { mock<FaceAuthenticationLogger>() }
val Kosmos.deviceEntryFaceAuthInteractor by
    Kosmos.Fixture {
        SystemUIDeviceEntryFaceAuthInteractor(
            context = applicationContext,
            applicationScope = applicationCoroutineScope,
            mainDispatcher = testDispatcher,
            repository = deviceEntryFaceAuthRepository,
            primaryBouncerInteractor = { primaryBouncerInteractor },
            alternateBouncerInteractor = alternateBouncerInteractor,
            keyguardTransitionInteractor = keyguardTransitionInteractor,
            faceAuthenticationLogger = faceAuthLogger,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            deviceEntryFingerprintAuthRepository = deviceEntryFingerprintAuthRepository,
            userRepository = userRepository,
            facePropertyRepository = facePropertyRepository,
            faceWakeUpTriggersConfig = faceWakeUpTriggersConfig,
            powerInteractor = powerInteractor,
            biometricSettingsRepository = biometricSettingsRepository,
            trustManager = trustManager,
        )
    }
