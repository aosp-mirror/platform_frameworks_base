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

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.PromptContentViewWithMoreOptionsButton
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.PromptVerticalListContentView
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PromptSelectorInteractorImplTest : SysuiTestCase() {
    companion object {
        private const val TITLE = "hey there"
        private const val SUBTITLE = "ok"
        private const val DESCRIPTION = "football"
        private const val NEGATIVE_TEXT = "escape"

        private const val USER_ID = 8
        private const val REQUEST_ID = 8L
        private const val CHALLENGE = 999L
        private const val OP_PACKAGE_NAME = "biometric.testapp"
        private val componentNameOverriddenForConfirmDeviceCredentialActivity =
            ComponentName("not.com.android.settings", "testapp")
    }

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils

    private val testScope = TestScope()
    private val fingerprintRepository = FakeFingerprintPropertyRepository()
    private val promptRepository = FakePromptRepository()
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var displayStateRepository: FakeDisplayStateRepository
    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var interactor: PromptSelectorInteractor

    @Before
    fun setup() {
        displayStateRepository = FakeDisplayStateRepository()
        displayRepository = FakeDisplayRepository()
        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository,
                displayRepository,
            )
        interactor =
            PromptSelectorInteractorImpl(
                fingerprintRepository,
                displayStateInteractor,
                promptRepository,
                lockPatternUtils
            )
    }

    private fun basicPromptInfo() =
        PromptInfo().apply {
            title = TITLE
            subtitle = SUBTITLE
            description = DESCRIPTION
            negativeButtonText = NEGATIVE_TEXT
            isConfirmationRequested = true
            isDeviceCredentialAllowed = true
            authenticators = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        }

    private val modalities =
        BiometricModalities(
            fingerprintProperties = fingerprintSensorPropertiesInternal().first(),
            faceProperties = faceSensorPropertiesInternal().first(),
        )

    @Test
    fun useBiometricsAndReset() =
        testScope.runTest { useBiometricsAndReset(allowCredentialFallback = true) }

    @Test
    fun useBiometricsAndResetWithoutFallback() =
        testScope.runTest { useBiometricsAndReset(allowCredentialFallback = false) }

    @Test
    fun useBiometricsAndResetOnConfirmDeviceCredentialActivity() =
        testScope.runTest {
            useBiometricsAndReset(
                allowCredentialFallback = true,
                setComponentNameForConfirmDeviceCredentialActivity = true
            )
        }

    private fun TestScope.useBiometricsAndReset(
        allowCredentialFallback: Boolean,
        setComponentNameForConfirmDeviceCredentialActivity: Boolean = false
    ) {
        setUserCredentialType(isPassword = true)

        val confirmationRequired = true
        val info =
            basicPromptInfo().apply {
                isConfirmationRequested = confirmationRequired
                authenticators =
                    if (allowCredentialFallback) {
                        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                    } else {
                        Authenticators.BIOMETRIC_STRONG
                    }
                isDeviceCredentialAllowed = allowCredentialFallback
                componentNameForConfirmDeviceCredentialActivity =
                    if (setComponentNameForConfirmDeviceCredentialActivity)
                        componentNameOverriddenForConfirmDeviceCredentialActivity
                    else null
            }

        val currentPrompt by collectLastValue(interactor.prompt)
        val promptKind by collectLastValue(interactor.promptKind)
        val isCredentialAllowed by collectLastValue(interactor.isCredentialAllowed)
        val credentialKind by collectLastValue(interactor.credentialKind)
        val isConfirmationRequired by collectLastValue(interactor.isConfirmationRequired)

        assertThat(currentPrompt).isNull()

        interactor.setPrompt(
            info,
            USER_ID,
            REQUEST_ID,
            modalities,
            CHALLENGE,
            OP_PACKAGE_NAME,
            onSwitchToCredential = false,
            isLandscape = false,
        )

        assertThat(currentPrompt).isNotNull()
        assertThat(currentPrompt?.title).isEqualTo(TITLE)
        assertThat(currentPrompt?.description).isEqualTo(DESCRIPTION)
        assertThat(currentPrompt?.subtitle).isEqualTo(SUBTITLE)
        assertThat(currentPrompt?.negativeButtonText).isEqualTo(NEGATIVE_TEXT)
        assertThat(currentPrompt?.opPackageName).isEqualTo(OP_PACKAGE_NAME)
        assertThat(promptKind!!.isBiometric()).isTrue()
        assertThat(currentPrompt?.componentNameForConfirmDeviceCredentialActivity)
            .isEqualTo(
                if (setComponentNameForConfirmDeviceCredentialActivity)
                    componentNameOverriddenForConfirmDeviceCredentialActivity
                else null
            )

        if (allowCredentialFallback) {
            assertThat(credentialKind).isSameInstanceAs(PromptKind.Password)
            assertThat(isCredentialAllowed).isTrue()
        } else {
            assertThat(credentialKind).isEqualTo(PromptKind.None)
            assertThat(isCredentialAllowed).isFalse()
        }
        assertThat(isConfirmationRequired).isEqualTo(confirmationRequired)

        interactor.resetPrompt(REQUEST_ID)
        verifyUnset()
    }

    @Test
    fun usePinCredentialAndReset() = testScope.runTest { useCredentialAndReset(PromptKind.Pin) }

    @Test
    fun usePatternCredentialAndReset() =
        testScope.runTest { useCredentialAndReset(PromptKind.Pattern) }

    @Test
    fun usePasswordCredentialAndReset() =
        testScope.runTest { useCredentialAndReset(PromptKind.Password) }

    @Test
    fun promptKind_isBiometric_whenBiometricAllowed() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt()

            assertThat(promptKind?.isOnePanePortraitBiometric()).isTrue()

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isBiometricTwoPane_whenBiometricAllowed_landscape() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)
            displayStateRepository.setIsLargeScreen(false)
            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt()

            assertThat(promptKind?.isTwoPaneLandscapeBiometric()).isTrue()

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isBiometricOnePane_whenBiometricAllowed_largeScreenLandscape() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)
            displayStateRepository.setIsLargeScreen(true)
            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt()

            assertThat(promptKind?.isOnePaneLargeScreenLandscapeBiometric()).isTrue()

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isCredential_onSwitchToCredential() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt(onSwitchToCredential = true)

            assertThat(promptKind).isEqualTo(PromptKind.Password)

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isCredential_whenBiometricIsNotAllowed() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)
            val info =
                basicPromptInfo().apply {
                    isDeviceCredentialAllowed = true
                    authenticators = Authenticators.DEVICE_CREDENTIAL
                }

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt(info)

            assertThat(promptKind).isEqualTo(PromptKind.Password)

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isCredential_whenBiometricIsNotAllowed_withMoreOptionsButton() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)
            val info =
                basicPromptInfo().apply {
                    isDeviceCredentialAllowed = true
                    authenticators = Authenticators.DEVICE_CREDENTIAL
                    contentView =
                        PromptContentViewWithMoreOptionsButton.Builder()
                            .setMoreOptionsButtonListener(fakeExecutor) { _, _ -> }
                            .build()
                }

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt(info)

            assertThat(promptKind).isEqualTo(PromptKind.Password)

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    @Test
    fun promptKind_isBiometric_whenBiometricIsNotAllowed_withVerticalList() =
        testScope.runTest {
            setUserCredentialType(isPassword = true)
            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            val info =
                basicPromptInfo().apply {
                    isDeviceCredentialAllowed = true
                    authenticators = Authenticators.DEVICE_CREDENTIAL
                    contentView = PromptVerticalListContentView.Builder().build()
                }

            val promptKind by collectLastValue(interactor.promptKind)
            assertThat(promptKind).isEqualTo(PromptKind.None)

            setPrompt(info)

            assertThat(promptKind?.isOnePaneNoSensorLandscapeBiometric()).isTrue()

            interactor.resetPrompt(REQUEST_ID)
            verifyUnset()
        }

    private fun setPrompt(
        info: PromptInfo = basicPromptInfo(),
        onSwitchToCredential: Boolean = false
    ) {
        interactor.setPrompt(
            info,
            USER_ID,
            REQUEST_ID,
            modalities,
            CHALLENGE,
            OP_PACKAGE_NAME,
            onSwitchToCredential = onSwitchToCredential,
            isLandscape =
                displayStateRepository.currentRotation.value == DisplayRotation.ROTATION_90 ||
                    displayStateRepository.currentRotation.value == DisplayRotation.ROTATION_270,
        )
    }

    private fun TestScope.useCredentialAndReset(kind: PromptKind) {
        setUserCredentialType(
            isPin = kind == PromptKind.Pin,
            isPassword = kind == PromptKind.Password,
        )

        val info =
            PromptInfo().apply {
                title = TITLE
                subtitle = SUBTITLE
                description = DESCRIPTION
                negativeButtonText = NEGATIVE_TEXT
                authenticators = Authenticators.DEVICE_CREDENTIAL
                isDeviceCredentialAllowed = true
            }

        val currentPrompt by collectLastValue(interactor.prompt)
        val credentialKind by collectLastValue(interactor.credentialKind)

        assertThat(currentPrompt).isNull()

        interactor.setPrompt(
            info,
            USER_ID,
            REQUEST_ID,
            BiometricModalities(),
            CHALLENGE,
            OP_PACKAGE_NAME,
            onSwitchToCredential = false,
            isLandscape = false,
        )

        // not using biometrics, should be null with no fallback option
        assertThat(currentPrompt).isNull()
        assertThat(credentialKind).isEqualTo(PromptKind.None)

        interactor.resetPrompt(REQUEST_ID)
        verifyUnset()
    }

    private fun TestScope.verifyUnset() {
        val currentPrompt by collectLastValue(interactor.prompt)
        val promptKind by collectLastValue(interactor.promptKind)
        val isCredentialAllowed by collectLastValue(interactor.isCredentialAllowed)
        val credentialKind by collectLastValue(interactor.credentialKind)
        val isConfirmationRequired by collectLastValue(interactor.isConfirmationRequired)

        assertThat(currentPrompt).isNull()
        assertThat(promptKind).isEqualTo(PromptKind.None)
        assertThat(isCredentialAllowed).isFalse()
        assertThat(credentialKind).isEqualTo(PromptKind.None)
        assertThat(isConfirmationRequired).isFalse()
    }

    private fun setUserCredentialType(isPin: Boolean = false, isPassword: Boolean = false) {
        whenever(lockPatternUtils.getKeyguardStoredPasswordQuality(any()))
            .thenReturn(
                when {
                    isPin -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    isPassword -> DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                    else -> DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                }
            )
    }
}
