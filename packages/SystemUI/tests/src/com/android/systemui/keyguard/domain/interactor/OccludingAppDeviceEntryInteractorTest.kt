/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricSourceType
import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.keyguard.util.IndicationHelper
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class OccludingAppDeviceEntryInteractorTest : SysuiTestCase() {

    private lateinit var underTest: OccludingAppDeviceEntryInteractor
    private lateinit var testScope: TestScope
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var fingerprintAuthRepository: FakeDeviceEntryFingerprintAuthRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var trustRepository: FakeTrustRepository
    private lateinit var powerRepository: FakePowerRepository
    private lateinit var powerInteractor: PowerInteractor

    @Mock private lateinit var indicationHelper: IndicationHelper
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var mockedContext: Context
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        fingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        keyguardRepository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        configurationRepository = FakeConfigurationRepository()
        featureFlags = FakeFeatureFlags()
        trustRepository = FakeTrustRepository()
        powerRepository = FakePowerRepository()
        powerInteractor =
            PowerInteractor(
                powerRepository,
                falsingCollector = mock(),
                screenOffAnimationController = mock(),
                statusBarStateController = mock(),
            )

        underTest =
            OccludingAppDeviceEntryInteractor(
                BiometricMessageInteractor(
                    mContext.resources,
                    fingerprintAuthRepository,
                    fingerprintPropertyRepository,
                    indicationHelper,
                    keyguardUpdateMonitor,
                ),
                fingerprintAuthRepository,
                KeyguardInteractorFactory.create(
                        featureFlags = featureFlags,
                        repository = keyguardRepository,
                        bouncerRepository = bouncerRepository,
                        configurationRepository = configurationRepository,
                        sceneInteractor =
                            mock { whenever(transitioningTo).thenReturn(MutableStateFlow(null)) },
                        powerInteractor = powerInteractor,
                    )
                    .keyguardInteractor,
                PrimaryBouncerInteractor(
                    bouncerRepository,
                    primaryBouncerView = mock(),
                    mainHandler = mock(),
                    keyguardStateController = mock(),
                    keyguardSecurityModel = mock(),
                    primaryBouncerCallbackInteractor = mock(),
                    falsingCollector = mock(),
                    dismissCallbackRegistry = mock(),
                    context,
                    keyguardUpdateMonitor,
                    trustRepository,
                    testScope.backgroundScope,
                    mSelectedUserInteractor,
                    deviceEntryFaceAuthInteractor = mock(),
                ),
                AlternateBouncerInteractor(
                    statusBarStateController = mock(),
                    keyguardStateController = mock(),
                    bouncerRepository,
                    FakeFingerprintPropertyRepository(),
                    biometricSettingsRepository,
                    FakeSystemClock(),
                    keyguardUpdateMonitor,
                    scope = testScope.backgroundScope,
                ),
                testScope.backgroundScope,
                mockedContext,
                activityStarter,
                powerInteractor,
            )
    }

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
            givenPrimaryAuthRequired(false)
            runCurrent()
            // WHEN a fp failure come in
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            // THEN message set to failure
            assertThat(message?.type).isEqualTo(BiometricMessageType.FAIL)

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
            givenPrimaryAuthRequired(false)
            runCurrent()

            // ERROR message
            fingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_CANCELED,
                    "testError",
                )
            )
            assertThat(message?.source).isEqualTo(BiometricSourceType.FINGERPRINT)
            assertThat(message?.id).isEqualTo(FingerprintManager.FINGERPRINT_ERROR_CANCELED)
            assertThat(message?.message).isEqualTo("testError")
            assertThat(message?.type).isEqualTo(BiometricMessageType.ERROR)

            // HELP message
            fingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ACQUIRED_PARTIAL,
                    "testHelp",
                )
            )
            assertThat(message?.source).isEqualTo(BiometricSourceType.FINGERPRINT)
            assertThat(message?.id).isEqualTo(FingerprintManager.FINGERPRINT_ACQUIRED_PARTIAL)
            assertThat(message?.message).isEqualTo("testHelp")
            assertThat(message?.type).isEqualTo(BiometricMessageType.HELP)

            // FAIL message
            fingerprintAuthRepository.setAuthenticationStatus(FailFingerprintAuthenticationStatus)
            assertThat(message?.source).isEqualTo(BiometricSourceType.FINGERPRINT)
            assertThat(message?.id)
                .isEqualTo(KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED)
            assertThat(message?.type).isEqualTo(BiometricMessageType.FAIL)
        }

    @Test
    fun message_fpError_lockoutFilteredOut() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            givenOnOccludingApp(true)
            givenPrimaryAuthRequired(false)
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

    private fun givenOnOccludingApp(isOnOccludingApp: Boolean) {
        powerRepository.setInteractive(true)
        keyguardRepository.setKeyguardOccluded(isOnOccludingApp)
        keyguardRepository.setKeyguardShowing(isOnOccludingApp)
        keyguardRepository.setDreaming(false)
        bouncerRepository.setPrimaryShow(!isOnOccludingApp)
        bouncerRepository.setAlternateVisible(!isOnOccludingApp)
    }

    private fun givenPrimaryAuthRequired(required: Boolean) {
        whenever(keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
            .thenReturn(!required)
    }

    private fun verifyGoToHomeScreen() {
        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)
        verify(mockedContext).startActivity(intentCaptor.capture())

        assertThat(intentCaptor.value.hasCategory(Intent.CATEGORY_HOME)).isTrue()
        assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_MAIN)
    }

    private fun verifyNeverGoToHomeScreen() {
        verify(mockedContext, never()).startActivity(any())
        verify(activityStarter, never())
            .dismissKeyguardThenExecute(any(OnDismissAction::class.java), isNull(), eq(false))
    }

    private fun verifyGoToHomeScreenOnDismiss() {
        val onDimissActionCaptor = ArgumentCaptor.forClass(OnDismissAction::class.java)
        verify(activityStarter)
            .dismissKeyguardThenExecute(onDimissActionCaptor.capture(), isNull(), eq(false))
        onDimissActionCaptor.value.onDismiss()

        verifyGoToHomeScreen()
    }
}
