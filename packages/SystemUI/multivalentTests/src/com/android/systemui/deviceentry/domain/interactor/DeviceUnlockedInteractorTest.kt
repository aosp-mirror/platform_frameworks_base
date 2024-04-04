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

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.domain.interactor.trustInteractor
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
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
            trustInteractor = kosmos.trustInteractor,
            faceAuthInteractor = kosmos.deviceEntryFaceAuthInteractor,
            fingerprintAuthInteractor = kosmos.deviceEntryFingerprintAuthInteractor,
            powerInteractor = kosmos.powerInteractor,
        )

    @Before
    fun setup() {
        kosmos.fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
    }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource).isNull()
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsPin_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.Fingerprint)
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsPin_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenFaceIsAuthenticatedWhileAwakeWithBypass_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            kosmos.powerInteractor.setAwakeForTest()

            kosmos.fakeDeviceEntryFaceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(true)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.FaceWithBypass)
        }

    @Test
    fun deviceUnlockStatus_whenFaceIsAuthenticatedWithoutBypass_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFaceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.FaceWithoutBypass)
        }

    @Test
    fun deviceUnlockStatus_whenFingerprintIsAuthenticated_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.Fingerprint)
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedByTrustAgent_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            kosmos.fakeUserRepository.setSelectedUserInfo(
                primaryUser,
                SelectionStatus.SELECTION_COMPLETE
            )

            kosmos.fakeTrustRepository.setCurrentUserTrusted(true)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.TrustAgent)
        }

    @Test
    fun deviceUnlockStatus_isResetToFalse_whenDeviceGoesToSleep() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    companion object {
        private const val primaryUserId = 1
        private val primaryUser = UserInfo(primaryUserId, "test user", UserInfo.FLAG_PRIMARY)

        private val secondaryUser = UserInfo(2, "secondary user", 0)
    }
}
