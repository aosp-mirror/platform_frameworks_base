/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.deviceentry.data

import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepositoryModule
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepositoryModule
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepositoryModule
import com.android.systemui.display.data.repository.FakeDisplayRepositoryModule
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepositoryModule
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepositoryModule
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepositoryModule
import com.android.systemui.keyguard.data.repository.FakeTrustRepositoryModule
import dagger.Module

@Module(
    includes =
        [
            FakeBiometricSettingsRepositoryModule::class,
            FakeDeviceEntryRepositoryModule::class,
            FakeDeviceEntryFaceAuthRepositoryModule::class,
            FakeDeviceEntryFingerprintAuthRepositoryModule::class,
            FakeDisplayRepositoryModule::class,
            FakeDisplayStateRepositoryModule::class,
            FakeFingerprintPropertyRepositoryModule::class,
            FakeTrustRepositoryModule::class,
        ]
)
object FakeDeviceEntryDataLayerModule
