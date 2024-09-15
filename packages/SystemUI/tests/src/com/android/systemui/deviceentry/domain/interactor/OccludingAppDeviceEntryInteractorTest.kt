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

import android.content.Intent
import android.content.mockedContext
import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.plugins.activityStarter
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class OccludingAppDeviceEntryInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.occludingAppDeviceEntryInteractor

    private val fingerprintAuthRepository = kosmos.deviceEntryFingerprintAuthRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val bouncerRepository = kosmos.keyguardBouncerRepository
    private val powerRepository = kosmos.fakePowerRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val mockedContext = kosmos.mockedContext
    private val mockedActivityStarter = kosmos.activityStarter

    @Test
    fun fingerprintSuccess_goToHomeScreen() =
        testScope.runTest {
            givenOnOccludingApp(true)
            fingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            verifyGoToHomeScreen()
        }

    @Test
    fun fingerprintSuccess_notInteractive_doesNotGoToHomeScreen() =
        testScope.runTest {
            givenOnOccludingApp(true)
            powerRepository.setInteractive(false)
            fingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            verifyNeverGoToHomeScreen()
        }

    @Test
    fun fingerprintSuccess_dreaming_doesNotGoToHomeScreen() =
        testScope.runTest {
            givenOnOccludingApp(true)
            keyguardRepository.setDreaming(true)
            fingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            verifyNeverGoToHomeScreen()
        }

    @Test
    fun fingerprintSuccess_notOnOccludingApp_doesNotGoToHomeScreen() =
        testScope.runTest {
            givenOnOccludingApp(false)
            fingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            verifyNeverGoToHomeScreen()
        }

    @Test
    fun lockout_goToHomeScreenOnDismissAction() =
        testScope.runTest {
            givenOnOccludingApp(true)
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    "lockoutTest"
                )
            )
            runCurrent()
            verifyGoToHomeScreenOnDismiss()
        }

    @Test
    fun lockout_notOnOccludingApp_neverGoToHomeScreen() =
        testScope.runTest {
            givenOnOccludingApp(false)
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    "lockoutTest"
                )
            )
            runCurrent()
            verifyNeverGoToHomeScreen()
        }

    @Test
    fun message_fpFailOnOccludingApp_thenNotOnOccludingApp() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            givenOnOccludingApp(true)
            givenFingerprintAllowed(true)
            runCurrent()
            // WHEN a fp failure come in
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // GIVEN fingerprint shouldn't run
            givenOnOccludingApp(false)
            runCurrent()
            // WHEN another fp failure arrives
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)

            // THEN message set to null
            assertThat(message).isNull()
        }

    @Test
    fun message_fpErrorHelpFailOnOccludingApp() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            givenOnOccludingApp(true)
            givenFingerprintAllowed(true)
            runCurrent()

            // ERROR message
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    "testError",
                )
            )
            assertThat(message?.message).isEqualTo("testError")

            // HELP message
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ACQUIRED_PARTIAL,
                    "testHelp",
                )
            )
            assertThat(message?.message).isEqualTo("testHelp")

            // FAIL message
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message?.message).isNotEqualTo("testHelp")
        }

    @Test
    fun message_fpError_lockoutFilteredOut() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            givenOnOccludingApp(true)
            givenFingerprintAllowed(true)
            runCurrent()

            // permanent lockout error message
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT,
                    "testPermanentLockoutMessageFiltered",
                )
            )
            assertThat(message).isNull()

            // temporary lockout error message
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    "testLockoutMessageFiltered",
                )
            )
            assertThat(message).isNull()
        }

    @Test
    fun noMessage_fpErrorsWhileDozing() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            givenOnOccludingApp(true)
            givenFingerprintAllowed(true)
            keyguardRepository.setIsDozing(true)
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.DOZING,
                testScope
            )
            runCurrent()

            // ERROR message
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE,
                    "testError",
                )
            )
            assertThat(message).isNull()

            // HELP message
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ACQUIRED_PARTIAL,
                    "testHelp",
                )
            )
            assertThat(message).isNull()

            // FAIL message
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message).isNull()
        }

    private suspend fun givenOnOccludingApp(isOnOccludingApp: Boolean) {
        powerRepository.setInteractive(true)
        keyguardRepository.setIsDozing(false)
        keyguardRepository.setKeyguardOccluded(isOnOccludingApp)
        keyguardRepository.setKeyguardShowing(isOnOccludingApp)
        keyguardRepository.setDreaming(false)
        bouncerRepository.setPrimaryShow(!isOnOccludingApp)
        bouncerRepository.setAlternateVisible(!isOnOccludingApp)

        if (isOnOccludingApp) {
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope
            )
        } else {
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.LOCKSCREEN,
                testScope
            )
        }
    }

    private fun givenFingerprintAllowed(allowed: Boolean) {
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(allowed)
    }

    private fun verifyGoToHomeScreen() {
        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(mockedContext).startActivity(intentCaptor.capture())

        assertThat(intentCaptor.value.hasCategory(Intent.CATEGORY_HOME)).isTrue()
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_MAIN)
    }

    private fun verifyNeverGoToHomeScreen() {
        verify(mockedContext, never()).startActivity(any())
        verify(mockedActivityStarter, never())
            .dismissKeyguardThenExecute(any(OnDismissAction::class.java), isNull(), eq(false))
    }

    private fun verifyGoToHomeScreenOnDismiss() {
        val onDimissActionCaptor = ArgumentCaptor.forClass(OnDismissAction::class.java)
        verify(mockedActivityStarter)
            .dismissKeyguardThenExecute(onDimissActionCaptor.capture(), isNull(), eq(false))
        onDimissActionCaptor.value.onDismiss()

        verifyGoToHomeScreen()
    }
}
