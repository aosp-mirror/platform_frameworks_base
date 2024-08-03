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
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository

    @Test
    fun isSensorUnderDisplay() =
        testScope.runTest {
            val isUdfps by collectLastValue(underTest.isSensorUnderDisplay)
            fingerprintPropertyRepository.supportsUdfps()
            assertThat(isUdfps).isTrue()
        }

    @Test
    fun isSensorUnderDisplay_rear() =
        testScope.runTest {
            val isUdfps by collectLastValue(underTest.isSensorUnderDisplay)
            fingerprintPropertyRepository.supportsRearFps()
            assertThat(isUdfps).isFalse()
        }

    @Test
    fun isSensorUnderDisplay_side() =
        testScope.runTest {
            val isUdfps by collectLastValue(underTest.isSensorUnderDisplay)
            fingerprintPropertyRepository.supportsSideFps()
            assertThat(isUdfps).isFalse()
        }
}
