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

package com.android.systemui.deviceentry.domain.interactor

import android.content.res.mainResources
import com.android.systemui.biometrics.domain.interactor.fingerprintPropertyInteractor
import com.android.systemui.keyguard.domain.interactor.devicePostureInteractor
import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val Kosmos.biometricMessageInteractor by
    Kosmos.Fixture {
        BiometricMessageInteractor(
            resources = mainResources,
            fingerprintAuthInteractor = deviceEntryFingerprintAuthInteractor,
            fingerprintPropertyInteractor = fingerprintPropertyInteractor,
            faceAuthInteractor = deviceEntryFaceAuthInteractor,
            biometricSettingsInteractor = deviceEntryBiometricSettingsInteractor,
            faceHelpMessageDeferralInteractor = faceHelpMessageDeferralInteractor,
            devicePostureInteractor = devicePostureInteractor,
        )
    }
