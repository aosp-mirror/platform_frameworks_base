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

import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.deviceentry.data.repository.deviceEntryRepository
import com.android.systemui.flags.fakeSystemPropertiesHelper
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.trustInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository

val Kosmos.deviceUnlockedInteractor by Fixture {
    DeviceUnlockedInteractor(
            authenticationInteractor = authenticationInteractor,
            repository = deviceEntryRepository,
            trustInteractor = trustInteractor,
            faceAuthInteractor = deviceEntryFaceAuthInteractor,
            fingerprintAuthInteractor = deviceEntryFingerprintAuthInteractor,
            powerInteractor = powerInteractor,
            biometricSettingsInteractor = deviceEntryBiometricSettingsInteractor,
            systemPropertiesHelper = fakeSystemPropertiesHelper,
            userAwareSecureSettingsRepository = userAwareSecureSettingsRepository,
            keyguardInteractor = keyguardInteractor,
        )
        .apply { activateIn(testScope) }
}
