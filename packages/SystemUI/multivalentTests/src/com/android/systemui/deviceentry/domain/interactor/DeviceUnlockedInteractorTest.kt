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
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceUnlockedInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val authenticationRepository = kosmos.fakeAuthenticationRepository
    private val deviceEntryRepository = kosmos.fakeDeviceEntryRepository

    val underTest =
        DeviceUnlockedInteractor(
            applicationScope = testScope.backgroundScope,
            authenticationInteractor = kosmos.authenticationInteractor,
            deviceEntryRepository = deviceEntryRepository,
        )

    @Test
    fun isDeviceUnlocked_whenUnlockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(true)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isDeviceUnlocked_whenUnlockedAndAuthMethodIsPin_isTrue() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(true)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isDeviceUnlocked_whenUnlockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(true)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun isDeviceUnlocked_whenLockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(false)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isDeviceUnlocked_whenLockedAndAuthMethodIsPin_isFalse() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(false)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun isDeviceUnlocked_whenLockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isDeviceUnlocked)

            deviceEntryRepository.setUnlocked(false)
            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)

            assertThat(isUnlocked).isFalse()
        }
}
