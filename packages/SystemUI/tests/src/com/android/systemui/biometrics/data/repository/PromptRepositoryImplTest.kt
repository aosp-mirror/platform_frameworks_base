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

package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.PromptInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

private const val USER_ID = 9
private const val CHALLENGE = 90L
private const val OP_PACKAGE_NAME = "biometric.testapp"

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PromptRepositoryImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val testScope = TestScope()
    private val faceSettings = FakeFaceSettingsRepository()

    @Mock private lateinit var authController: AuthController

    private lateinit var repository: PromptRepositoryImpl

    @Before
    fun setup() {
        repository = PromptRepositoryImpl(faceSettings, authController)
    }

    @Test
    fun isShowing() =
        testScope.runTest {
            whenever(authController.isShowing).thenReturn(true)

            val values = mutableListOf<Boolean>()
            val job = launch { repository.isShowing.toList(values) }
            runCurrent()

            assertThat(values).containsExactly(true)

            withArgCaptor<AuthController.Callback> {
                verify(authController).addCallback(capture())

                value.onBiometricPromptShown()
                runCurrent()
                assertThat(values).containsExactly(true, true)

                value.onBiometricPromptDismissed()
                runCurrent()
                assertThat(values).containsExactly(true, true, false).inOrder()

                job.cancel()
                runCurrent()
                verify(authController).removeCallback(eq(value))
            }
        }

    @Test
    fun isConfirmationRequired_whenNotForced() =
        testScope.runTest {
            faceSettings.setUserSettings(USER_ID, alwaysRequireConfirmationInApps = false)
            val isConfirmationRequired by collectLastValue(repository.isConfirmationRequired)

            for (case in listOf(true, false)) {
                repository.setPrompt(
                    PromptInfo().apply { isConfirmationRequested = case },
                    USER_ID,
                    CHALLENGE,
                    PromptKind.Biometric(),
                    OP_PACKAGE_NAME
                )

                assertThat(isConfirmationRequired).isEqualTo(case)
            }
        }

    @Test
    fun isConfirmationRequired_whenForced() =
        testScope.runTest {
            faceSettings.setUserSettings(USER_ID, alwaysRequireConfirmationInApps = true)
            val isConfirmationRequired by collectLastValue(repository.isConfirmationRequired)

            for (case in listOf(true, false)) {
                repository.setPrompt(
                    PromptInfo().apply { isConfirmationRequested = case },
                    USER_ID,
                    CHALLENGE,
                    PromptKind.Biometric(),
                    OP_PACKAGE_NAME
                )

                assertThat(isConfirmationRequired).isTrue()
            }
        }

    @Test
    fun setsAndUnsetsPrompt() =
        testScope.runTest {
            val kind = PromptKind.Pin
            val promptInfo = PromptInfo()

            repository.setPrompt(promptInfo, USER_ID, CHALLENGE, kind, OP_PACKAGE_NAME)

            assertThat(repository.kind.value).isEqualTo(kind)
            assertThat(repository.userId.value).isEqualTo(USER_ID)
            assertThat(repository.challenge.value).isEqualTo(CHALLENGE)
            assertThat(repository.promptInfo.value).isSameInstanceAs(promptInfo)
            assertThat(repository.opPackageName.value).isEqualTo(OP_PACKAGE_NAME)

            repository.unsetPrompt()

            assertThat(repository.promptInfo.value).isNull()
            assertThat(repository.userId.value).isNull()
            assertThat(repository.challenge.value).isNull()
            assertThat(repository.opPackageName.value).isNull()
        }
}
