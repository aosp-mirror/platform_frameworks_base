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
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.PromptInfo
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
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

private const val TITLE = "hey there"
private const val SUBTITLE = "ok"
private const val DESCRIPTION = "football"
private const val NEGATIVE_TEXT = "escape"

private const val USER_ID = 8
private const val CHALLENGE = 999L

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PromptSelectorInteractorImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils

    private val testScope = TestScope()
    private val fingerprintRepository = FakeFingerprintPropertyRepository()
    private val promptRepository = FakePromptRepository()

    private lateinit var interactor: PromptSelectorInteractor

    @Before
    fun setup() {
        interactor =
            PromptSelectorInteractorImpl(fingerprintRepository, promptRepository, lockPatternUtils)
    }

    @Test
    fun useBiometricsAndReset() =
        testScope.runTest { useBiometricsAndReset(allowCredentialFallback = true) }

    @Test
    fun useBiometricsAndResetWithoutFallback() =
        testScope.runTest { useBiometricsAndReset(allowCredentialFallback = false) }

    private fun TestScope.useBiometricsAndReset(allowCredentialFallback: Boolean) {
        setUserCredentialType(isPassword = true)

        val confirmationRequired = true
        val info =
            PromptInfo().apply {
                title = TITLE
                subtitle = SUBTITLE
                description = DESCRIPTION
                negativeButtonText = NEGATIVE_TEXT
                isConfirmationRequested = confirmationRequired
                authenticators =
                    if (allowCredentialFallback) {
                        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                    } else {
                        Authenticators.BIOMETRIC_STRONG
                    }
                isDeviceCredentialAllowed = allowCredentialFallback
            }
        val modalities =
            BiometricModalities(
                fingerprintProperties = fingerprintSensorPropertiesInternal().first(),
                faceProperties = faceSensorPropertiesInternal().first(),
            )

        val currentPrompt by collectLastValue(interactor.prompt)
        val credentialKind by collectLastValue(interactor.credentialKind)
        val isCredentialAllowed by collectLastValue(interactor.isCredentialAllowed)
        val isExplicitConfirmationRequired by collectLastValue(interactor.isConfirmationRequired)

        assertThat(currentPrompt).isNull()

        interactor.useBiometricsForAuthentication(info, USER_ID, CHALLENGE, modalities)

        assertThat(currentPrompt).isNotNull()
        assertThat(currentPrompt?.title).isEqualTo(TITLE)
        assertThat(currentPrompt?.description).isEqualTo(DESCRIPTION)
        assertThat(currentPrompt?.subtitle).isEqualTo(SUBTITLE)
        assertThat(currentPrompt?.negativeButtonText).isEqualTo(NEGATIVE_TEXT)

        if (allowCredentialFallback) {
            assertThat(credentialKind).isSameInstanceAs(PromptKind.Password)
            assertThat(isCredentialAllowed).isTrue()
        } else {
            assertThat(credentialKind).isEqualTo(PromptKind.Biometric())
            assertThat(isCredentialAllowed).isFalse()
        }
        assertThat(isExplicitConfirmationRequired).isEqualTo(confirmationRequired)

        interactor.resetPrompt()
        verifyUnset()
    }

    @Test
    fun usePinCredentialAndReset() =
        testScope.runTest { useCredentialAndReset(Utils.CREDENTIAL_PIN) }

    @Test
    fun usePattermCredentialAndReset() =
        testScope.runTest { useCredentialAndReset(Utils.CREDENTIAL_PATTERN) }

    @Test
    fun usePasswordCredentialAndReset() =
        testScope.runTest { useCredentialAndReset(Utils.CREDENTIAL_PASSWORD) }

    private fun TestScope.useCredentialAndReset(@Utils.CredentialType kind: Int) {
        setUserCredentialType(
            isPin = kind == Utils.CREDENTIAL_PIN,
            isPassword = kind == Utils.CREDENTIAL_PASSWORD,
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

        interactor.useCredentialsForAuthentication(info, kind, USER_ID, CHALLENGE)

        // not using biometrics, should be null with no fallback option
        assertThat(currentPrompt).isNull()
        assertThat(credentialKind).isEqualTo(PromptKind.Biometric())

        interactor.resetPrompt()
        verifyUnset()
    }

    private fun TestScope.verifyUnset() {
        val currentPrompt by collectLastValue(interactor.prompt)
        val credentialKind by collectLastValue(interactor.credentialKind)

        assertThat(currentPrompt).isNull()

        val kind = credentialKind as? PromptKind.Biometric
        assertThat(kind).isNotNull()
        assertThat(kind?.activeModalities?.isEmpty).isTrue()
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
