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

package com.android.systemui.authentication.domain.interactor

import android.app.admin.DevicePolicyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AuthenticationInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val underTest = utils.authenticationInteractor()

    @Test
    fun authenticationMethod() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(AuthenticationMethodModel.Pin)

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Password)
        }

    @Test
    fun authenticationMethod_none_whenLockscreenDisabled() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.None)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.None)
        }

    @Test
    fun authenticate_withCorrectPin_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPin_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(listOf(9, 8, 7, 6, 5, 4)))
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test(expected = IllegalArgumentException::class)
    fun authenticate_withEmptyPin_throwsException() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            underTest.authenticate(listOf())
        }

    @Test
    fun authenticate_withCorrectMaxLengthPin_returnsTrue() =
        testScope.runTest {
            val pin = List(16) { 9 }
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                overrideCredential(pin)
            }

            assertThat(underTest.authenticate(pin)).isEqualTo(AuthenticationResult.SUCCEEDED)
        }

    @Test
    fun authenticate_withCorrectTooLongPin_returnsFalse() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures
            // that all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(List(17) { 9 }))
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun authenticate_withCorrectPassword_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList()))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPassword_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("alohomora".toList()))
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun authenticate_withCorrectPattern_returnsTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )

            assertThat(underTest.authenticate(FakeAuthenticationRepository.PATTERN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
        }

    @Test
    fun authenticate_withIncorrectPattern_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )

            assertThat(
                    underTest.authenticate(
                        listOf(
                            AuthenticationPatternCoordinate(x = 2, y = 0),
                            AuthenticationPatternCoordinate(x = 2, y = 1),
                            AuthenticationPatternCoordinate(x = 2, y = 2),
                            AuthenticationPatternCoordinate(x = 1, y = 2),
                        )
                    )
                )
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmPinAndShorterPin_returnsNull() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN.toMutableList().apply {
                            removeLast()
                        },
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmWrongPinCorrectLength_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN.map { it + 1 },
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmLongerPin_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN + listOf(7),
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPin_returnsTrue() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.SUCCEEDED)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPinButDuringThrottling_returnsNull() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            val isUnlocked by collectLastValue(utils.deviceEntryRepository.isUnlocked)
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
                setThrottleDuration(42)
            }

            val authResult =
                underTest.authenticate(
                    FakeAuthenticationRepository.DEFAULT_PIN,
                    tryAutoConfirm = true
                )

            assertThat(authResult).isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(isAutoConfirmEnabled).isFalse()
            assertThat(isUnlocked).isFalse()
            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun tryAutoConfirm_withoutAutoConfirmButCorrectPin_returnsNull() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(false)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.SKIPPED)
        }

    @Test
    fun tryAutoConfirm_withoutCorrectPassword_returnsNull() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList(), tryAutoConfirm = true))
                .isEqualTo(AuthenticationResult.SKIPPED)
        }

    @Test
    fun throttling() =
        testScope.runTest {
            val throttling by collectLastValue(underTest.throttling)
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())

            // Make many wrong attempts, but just shy of what's needed to get throttled:
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING - 1) {
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
                assertThat(isThrottled).isFalse()
                assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
            }

            // Make one more wrong attempt, leading to throttling:
            underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
            assertThat(isThrottled).isTrue()
            assertThat(throttling)
                .isEqualTo(
                    AuthenticationThrottlingModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                        remainingMs = FakeAuthenticationRepository.THROTTLE_DURATION_MS,
                    )
                )

            // Correct PIN, but throttled, so doesn't attempt it:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(isThrottled).isTrue()
            assertThat(throttling)
                .isEqualTo(
                    AuthenticationThrottlingModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                        remainingMs = FakeAuthenticationRepository.THROTTLE_DURATION_MS,
                    )
                )

            // Move the clock forward to ALMOST skip the throttling, leaving one second to go:
            val throttleTimeoutSec =
                FakeAuthenticationRepository.THROTTLE_DURATION_MS.milliseconds.inWholeSeconds
                    .toInt()
            repeat(throttleTimeoutSec - 1) { time ->
                advanceTimeBy(1000)
                assertThat(isThrottled).isTrue()
                assertThat(throttling)
                    .isEqualTo(
                        AuthenticationThrottlingModel(
                            failedAttemptCount =
                                FakeAuthenticationRepository
                                    .MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                            remainingMs =
                                ((throttleTimeoutSec - (time + 1)).seconds.inWholeMilliseconds)
                                    .toInt(),
                        )
                    )
            }

            // Move the clock forward one more second, to completely finish the throttling period:
            advanceTimeBy(1000)
            assertThat(isThrottled).isFalse()
            assertThat(throttling)
                .isEqualTo(
                    AuthenticationThrottlingModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                        remainingMs = 0,
                    )
                )

            // Correct PIN and no longer throttled so unlocks successfully:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
        }

    @Test
    fun hintedPinLength_withoutAutoConfirm_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(false)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinTooShort_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                overrideCredential(
                    buildList {
                        repeat(utils.authenticationRepository.hintedPinLength - 1) { add(it + 1) }
                    }
                )
                setAutoConfirmFeatureEnabled(true)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinAtRightLength_isSameLength() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
                overrideCredential(
                    buildList {
                        repeat(utils.authenticationRepository.hintedPinLength) { add(it + 1) }
                    }
                )
            }

            assertThat(hintedPinLength).isEqualTo(utils.authenticationRepository.hintedPinLength)
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinTooLong_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                overrideCredential(
                    buildList {
                        repeat(utils.authenticationRepository.hintedPinLength + 1) { add(it + 1) }
                    }
                )
                setAutoConfirmFeatureEnabled(true)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun authenticate_withTooShortPassword() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            assertThat(
                    underTest.authenticate(
                        buildList {
                            repeat(utils.authenticationRepository.minPasswordLength - 1) { time ->
                                add("$time")
                            }
                        }
                    )
                )
                .isEqualTo(AuthenticationResult.SKIPPED)
        }
}
