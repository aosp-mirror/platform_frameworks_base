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
import com.android.systemui.authentication.shared.model.AuthenticationLockoutModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
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
    fun authenticate_withCorrectPin_succeeds() =
        testScope.runTest {
            val lockout by collectLastValue(underTest.lockout)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(lockout).isNull()
            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPin_fails() =
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
    fun authenticate_withCorrectMaxLengthPin_succeeds() =
        testScope.runTest {
            val pin = List(16) { 9 }
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                overrideCredential(pin)
            }

            assertThat(underTest.authenticate(pin)).isEqualTo(AuthenticationResult.SUCCEEDED)
        }

    @Test
    fun authenticate_withCorrectTooLongPin_fails() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures that
            // all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(List(17) { 9 }))
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun authenticate_withCorrectPassword_succeeds() =
        testScope.runTest {
            val lockout by collectLastValue(underTest.lockout)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList()))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(lockout).isNull()
            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(0)
        }

    @Test
    fun authenticate_withIncorrectPassword_fails() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("alohomora".toList()))
                .isEqualTo(AuthenticationResult.FAILED)
        }

    @Test
    fun authenticate_withCorrectPattern_succeeds() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )

            assertThat(underTest.authenticate(FakeAuthenticationRepository.PATTERN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
        }

    @Test
    fun authenticate_withIncorrectPattern_fails() =
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
            val lockout by collectLastValue(underTest.lockout)
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
            assertThat(lockout).isNull()
            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(0)
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
    fun tryAutoConfirm_withAutoConfirmCorrectPinButDuringLockout_returnsNull() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            val isUnlocked by collectLastValue(utils.deviceEntryRepository.isUnlocked)
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(AuthenticationMethodModel.Pin)
                setAutoConfirmFeatureEnabled(true)
                setLockoutDuration(42)
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
    fun isAutoConfirmEnabled_featureDisabled_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(false)

            assertThat(isAutoConfirmEnabled).isFalse()
        }

    @Test
    fun isAutoConfirmEnabled_featureEnabled_returnsTrue() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)

            assertThat(isAutoConfirmEnabled).isTrue()
        }

    @Test
    fun isAutoConfirmEnabled_featureEnabledButDisabledByLockout() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            val lockout by collectLastValue(underTest.lockout)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)

            // The feature is enabled.
            assertThat(isAutoConfirmEnabled).isTrue()

            // Make many wrong attempts to trigger lockout.
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
            }
            assertThat(lockout).isNotNull()
            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(1)

            // Lockout disabled auto-confirm.
            assertThat(isAutoConfirmEnabled).isFalse()

            // Move the clock forward one more second, to completely finish the lockout period:
            advanceTimeBy(FakeAuthenticationRepository.LOCKOUT_DURATION_MS + 1000L)
            assertThat(lockout).isNull()

            // Auto-confirm is still disabled, because lockout occurred at least once in this
            // session.
            assertThat(isAutoConfirmEnabled).isFalse()

            // Correct PIN and unlocks successfully, resetting the 'session'.
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)

            // Auto-confirm is re-enabled.
            assertThat(isAutoConfirmEnabled).isTrue()

            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(1)
        }

    @Test
    fun lockout() =
        testScope.runTest {
            val lockout by collectLastValue(underTest.lockout)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            assertThat(lockout).isNull()

            // Make many wrong attempts, but just shy of what's needed to get locked out:
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT - 1) {
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
                assertThat(lockout).isNull()
            }

            // Make one more wrong attempt, leading to lockout:
            underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
            assertThat(lockout)
                .isEqualTo(
                    AuthenticationLockoutModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT,
                        remainingSeconds = FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS,
                    )
                )
            assertThat(utils.authenticationRepository.lockoutStartedReportCount).isEqualTo(1)

            // Correct PIN, but locked out, so doesn't attempt it:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(lockout)
                .isEqualTo(
                    AuthenticationLockoutModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT,
                        remainingSeconds = FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS,
                    )
                )

            // Move the clock forward to ALMOST skip the lockout, leaving one second to go:
            val lockoutTimeoutSec = FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS
            repeat(FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS - 1) { time ->
                advanceTimeBy(1000)
                assertThat(lockout)
                    .isEqualTo(
                        AuthenticationLockoutModel(
                            failedAttemptCount =
                                FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT,
                            remainingSeconds = lockoutTimeoutSec - (time + 1),
                        )
                    )
            }

            // Move the clock forward one more second, to completely finish the lockout period:
            advanceTimeBy(1000)
            assertThat(lockout).isNull()

            // Correct PIN and no longer locked out so unlocks successfully:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(lockout).isNull()
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
