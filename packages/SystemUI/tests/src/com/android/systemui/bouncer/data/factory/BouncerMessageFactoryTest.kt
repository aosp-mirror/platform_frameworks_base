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

package com.android.systemui.bouncer.data.factory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.PIN
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.Password
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.Pattern
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEFAULT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class BouncerMessageFactoryTest : SysuiTestCase() {
    private lateinit var underTest: BouncerMessageFactory

    @Mock private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository

    @Mock private lateinit var securityModel: KeyguardSecurityModel

    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        underTest = BouncerMessageFactory(biometricSettingsRepository, securityModel)
    }

    @Test
    fun bouncerMessages_choosesTheRightMessage_basedOnSecurityModeAndFpAuthIsAllowed() =
        testScope.runTest {
            primaryMessage(PROMPT_REASON_DEFAULT, mode = PIN, fpAuthAllowed = false)
                .isEqualTo("Enter PIN")
            primaryMessage(PROMPT_REASON_DEFAULT, mode = PIN, fpAuthAllowed = true)
                .isEqualTo("Unlock with PIN or fingerprint")

            primaryMessage(PROMPT_REASON_DEFAULT, mode = Password, fpAuthAllowed = false)
                .isEqualTo("Enter password")
            primaryMessage(PROMPT_REASON_DEFAULT, mode = Password, fpAuthAllowed = true)
                .isEqualTo("Unlock with password or fingerprint")

            primaryMessage(PROMPT_REASON_DEFAULT, mode = Pattern, fpAuthAllowed = false)
                .isEqualTo("Draw pattern")
            primaryMessage(PROMPT_REASON_DEFAULT, mode = Pattern, fpAuthAllowed = true)
                .isEqualTo("Unlock with pattern or fingerprint")
        }

    @Test
    fun bouncerMessages_overridesSecondaryMessageValue() =
        testScope.runTest {
            val bouncerMessageModel =
                bouncerMessageModel(
                    PIN,
                    true,
                    PROMPT_REASON_DEFAULT,
                    secondaryMessageOverride = "face acquisition message"
                )!!
            assertThat(context.resources.getString(bouncerMessageModel.message!!.messageResId!!))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessageModel.secondaryMessage!!.message!!)
                .isEqualTo("face acquisition message")
        }

    @Test
    fun bouncerMessages_setsPrimaryAndSecondaryMessage_basedOnSecurityModeAndFpAuthIsAllowed() =
        testScope.runTest {
            primaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = PIN,
                    fpAuthAllowed = true
                )
                .isEqualTo("Wrong PIN. Try again.")
            secondaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = PIN,
                    fpAuthAllowed = true
                )
                .isEqualTo("Or unlock with fingerprint")

            primaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = Password,
                    fpAuthAllowed = true
                )
                .isEqualTo("Wrong password. Try again.")
            secondaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = Password,
                    fpAuthAllowed = true
                )
                .isEqualTo("Or unlock with fingerprint")

            primaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = Pattern,
                    fpAuthAllowed = true
                )
                .isEqualTo("Wrong pattern. Try again.")
            secondaryMessage(
                    PROMPT_REASON_INCORRECT_PRIMARY_AUTH_INPUT,
                    mode = Pattern,
                    fpAuthAllowed = true
                )
                .isEqualTo("Or unlock with fingerprint")
        }

    private fun primaryMessage(
        reason: Int,
        mode: KeyguardSecurityModel.SecurityMode,
        fpAuthAllowed: Boolean
    ): StringSubject {
        return assertThat(
            context.resources.getString(
                bouncerMessageModel(mode, fpAuthAllowed, reason)!!.message!!.messageResId!!
            )
        )!!
    }

    private fun secondaryMessage(
        reason: Int,
        mode: KeyguardSecurityModel.SecurityMode,
        fpAuthAllowed: Boolean
    ): StringSubject {
        return assertThat(
            context.resources.getString(
                bouncerMessageModel(mode, fpAuthAllowed, reason)!!.secondaryMessage!!.messageResId!!
            )
        )!!
    }

    private fun bouncerMessageModel(
        mode: KeyguardSecurityModel.SecurityMode,
        fpAuthAllowed: Boolean,
        reason: Int,
        secondaryMessageOverride: String? = null,
    ): BouncerMessageModel? {
        whenever(securityModel.getSecurityMode(0)).thenReturn(mode)
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(fpAuthAllowed)

        return underTest.createFromPromptReason(
            reason,
            0,
            secondaryMsgOverride = secondaryMessageOverride
        )
    }
}
