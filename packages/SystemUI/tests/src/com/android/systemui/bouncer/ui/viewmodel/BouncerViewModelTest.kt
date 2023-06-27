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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class BouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = utils.sceneInteractor(),
        )
    private val underTest = utils.bouncerViewModel(bouncerInteractor)

    @Test
    fun authMethod_nonNullForSecureMethods_nullForNotSecureMethods() =
        testScope.runTest {
            var authMethodViewModel: AuthMethodBouncerViewModel? = null

            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                val job = underTest.authMethod.onEach { authMethodViewModel = it }.launchIn(this)
                runCurrent()

                if (authMethod.isSecure) {
                    assertWithMessage("View-model unexpectedly null for auth method $authMethod")
                        .that(authMethodViewModel)
                        .isNotNull()
                } else {
                    assertWithMessage(
                            "View-model unexpectedly non-null for auth method $authMethod"
                        )
                        .that(authMethodViewModel)
                        .isNull()
                }

                job.cancel()
            }
        }

    @Test
    fun authMethod_reusesInstances() =
        testScope.runTest {
            val seen = mutableMapOf<AuthenticationMethodModel, AuthMethodBouncerViewModel>()
            val authMethodViewModel: AuthMethodBouncerViewModel? by
                collectLastValue(underTest.authMethod)
            // First pass, populate our "seen" map:
            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                authMethodViewModel?.let { seen[authMethod] = it }
            }

            // Second pass, assert same instances are reused:
            authMethodsToTest().forEach { authMethod ->
                utils.authenticationRepository.setAuthenticationMethod(authMethod)
                authMethodViewModel?.let { assertThat(it).isSameInstanceAs(seen[authMethod]) }
            }
        }

    @Test
    fun authMethodsToTest_returnsCompleteSampleOfAllAuthMethodTypes() {
        assertThat(authMethodsToTest().map { it::class }.toSet())
            .isEqualTo(AuthenticationMethodModel::class.sealedSubclasses.toSet())
    }

    @Test
    fun message() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            val throttling by collectLastValue(bouncerInteractor.throttling)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            assertThat(message?.isUpdateAnimated).isTrue()

            repeat(BouncerInteractor.THROTTLE_EVERY) {
                // Wrong PIN.
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(message?.isUpdateAnimated).isFalse()

            throttling?.totalDurationSec?.let { seconds -> advanceTimeBy(seconds * 1000L) }
            assertThat(message?.isUpdateAnimated).isTrue()
        }

    @Test
    fun isInputEnabled() =
        testScope.runTest {
            val isInputEnabled by
                collectLastValue(
                    underTest.authMethod.flatMapLatest { authViewModel ->
                        authViewModel?.isInputEnabled ?: emptyFlow()
                    }
                )
            val throttling by collectLastValue(bouncerInteractor.throttling)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            assertThat(isInputEnabled).isTrue()

            repeat(BouncerInteractor.THROTTLE_EVERY) {
                // Wrong PIN.
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(isInputEnabled).isFalse()

            throttling?.totalDurationSec?.let { seconds -> advanceTimeBy(seconds * 1000L) }
            assertThat(isInputEnabled).isTrue()
        }

    @Test
    fun throttlingDialogMessage() =
        testScope.runTest {
            val throttlingDialogMessage by collectLastValue(underTest.throttlingDialogMessage)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )

            repeat(BouncerInteractor.THROTTLE_EVERY) {
                // Wrong PIN.
                assertThat(throttlingDialogMessage).isNull()
                bouncerInteractor.authenticate(listOf(3, 4, 5, 6))
            }
            assertThat(throttlingDialogMessage).isNotEmpty()

            underTest.onThrottlingDialogDismissed()
            assertThat(throttlingDialogMessage).isNull()
        }

    private fun authMethodsToTest(): List<AuthenticationMethodModel> {
        return listOf(
            AuthenticationMethodModel.None,
            AuthenticationMethodModel.Swipe,
            AuthenticationMethodModel.Pin(1234),
            AuthenticationMethodModel.Password("password"),
            AuthenticationMethodModel.Pattern(
                listOf(AuthenticationMethodModel.Pattern.PatternCoordinate(1, 1))
            ),
        )
    }
}
