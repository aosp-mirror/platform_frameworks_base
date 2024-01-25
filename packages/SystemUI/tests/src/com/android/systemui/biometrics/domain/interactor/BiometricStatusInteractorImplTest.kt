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

package com.android.systemui.biometrics.domain.interactor

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.ComponentName
import android.hardware.biometrics.BiometricFingerprintConstants
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeBiometricStatusRepository
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.AuthenticationReason.SettingsOperations
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.shared.Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class BiometricStatusInteractorImplTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var activityTaskManager: ActivityTaskManager

    private lateinit var biometricStatusRepository: FakeBiometricStatusRepository
    private lateinit var underTest: BiometricStatusInteractorImpl

    private val testScope = TestScope(StandardTestDispatcher())

    @Before
    fun setup() {
        mSetFlagsRule.enableFlags(FLAG_SIDEFPS_CONTROLLER_REFACTOR)
        biometricStatusRepository = FakeBiometricStatusRepository()
        underTest = BiometricStatusInteractorImpl(activityTaskManager, biometricStatusRepository)
    }

    @Test
    fun updatesSfpsAuthenticationReason_whenBiometricPromptAuthenticationStarted() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            assertThat(sfpsAuthenticationReason)
                .isEqualTo(AuthenticationReason.BiometricPromptAuthentication)
        }

    @Test
    fun doesNotUpdateSfpsAuthenticationReason_whenDeviceEntryAuthenticationStarted() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.DeviceEntryAuthentication
            )
            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)
        }

    @Test
    fun updatesSfpsAuthenticationReason_whenOtherAuthenticationStarted() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.OtherAuthentication
            )
            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.OtherAuthentication)
        }

    @Test
    fun doesNotUpdateSfpsAuthenticationReason_whenOtherSettingsAuthenticationStarted() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            `when`(activityTaskManager.getTasks(Mockito.anyInt()))
                .thenReturn(listOf(fpSettingsTask()))
            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.SettingsAuthentication(SettingsOperations.OTHER)
            )
            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)
        }

    @Test
    fun updatesSfpsAuthenticationReason_whenEnrollmentAuthenticationStarted() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_FIND_SENSOR)
            )
            assertThat(sfpsAuthenticationReason)
                .isEqualTo(
                    AuthenticationReason.SettingsAuthentication(
                        SettingsOperations.ENROLL_FIND_SENSOR
                    )
                )

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_ENROLLING)
            )
            assertThat(sfpsAuthenticationReason)
                .isEqualTo(
                    AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_ENROLLING)
                )
        }

    @Test
    fun updatesFingerprintAuthenticationReason_whenAuthenticationStopped() =
        testScope.runTest {
            val sfpsAuthenticationReason by collectLastValue(underTest.sfpsAuthenticationReason)
            runCurrent()

            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            assertThat(sfpsAuthenticationReason).isEqualTo(AuthenticationReason.NotRunning)
        }

    @Test
    fun updatesFingerprintAcquiredStatusWhenBiometricPromptAuthenticationAcquired() =
        testScope.runTest {
            val fingerprintAcquiredStatus by collectLastValue(underTest.fingerprintAcquiredStatus)
            runCurrent()

            biometricStatusRepository.setFingerprintAcquiredStatus(
                AcquiredFingerprintAuthenticationStatus(
                    AuthenticationReason.BiometricPromptAuthentication,
                    BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                )
            )
            assertThat(fingerprintAcquiredStatus)
                .isEqualTo(
                    AcquiredFingerprintAuthenticationStatus(
                        AuthenticationReason.BiometricPromptAuthentication,
                        BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                    )
                )
        }
}

private fun fpSettingsTask() = settingsTask(".biometrics.fingerprint.FingerprintSettings")

private fun settingsTask(cls: String) =
    ActivityManager.RunningTaskInfo().apply {
        topActivity = ComponentName.createRelative("com.android.settings", cls)
    }
