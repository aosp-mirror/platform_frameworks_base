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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryFingerprintAuthInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.deviceEntryFingerprintAuthInteractor
    private val fingerprintAuthRepository = kosmos.deviceEntryFingerprintAuthRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository

    @Test
    fun isFingerprintAuthCurrentlyAllowed_allowedOnlyWhenItIsNotLockedOutAndAllowedBySettings() =
        testScope.runTest {
            val currentlyAllowed by collectLastValue(underTest.isFingerprintAuthCurrentlyAllowed)
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            fingerprintAuthRepository.setLockedOut(true)

            assertThat(currentlyAllowed).isFalse()

            fingerprintAuthRepository.setLockedOut(false)
            assertThat(currentlyAllowed).isTrue()

            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(false)
            assertThat(currentlyAllowed).isFalse()
        }

    @Test
    fun isFingerprintCurrentlyAllowedInBouncer_trueForNonUdfpsSensorTypes() =
        testScope.runTest {
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            val isFingerprintCurrentlyAllowedInBouncer by
                collectLastValue(underTest.isFingerprintCurrentlyAllowedOnBouncer)

            fingerprintPropertyRepository.supportsUdfps()
            assertThat(isFingerprintCurrentlyAllowedInBouncer).isFalse()

            fingerprintPropertyRepository.supportsRearFps()
            assertThat(isFingerprintCurrentlyAllowedInBouncer).isTrue()

            fingerprintPropertyRepository.supportsSideFps()
            assertThat(isFingerprintCurrentlyAllowedInBouncer).isTrue()
        }
}
