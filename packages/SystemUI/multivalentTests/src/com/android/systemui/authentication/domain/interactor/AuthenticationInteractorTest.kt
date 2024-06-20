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
import android.app.admin.flags.Flags as DevicePolicyFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.None
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Password
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pattern
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pin
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationWipeModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AuthenticationInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.authenticationInteractor

    private val onAuthenticationResult by
        testScope.collectLastValue(underTest.onAuthenticationResult)
    private val failedAuthenticationAttempts by
        testScope.collectLastValue(underTest.failedAuthenticationAttempts)

    @Test
    fun authenticationMethod() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()
            assertThat(authMethod).isEqualTo(Pin)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(Pin)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Password)

            assertThat(authMethod).isEqualTo(Password)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(Password)
        }

    @Test
    fun authenticationMethod_none_whenLockscreenDisabled() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(None)

            assertThat(authMethod).isEqualTo(None)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(None)
        }

    @Test
    fun authenticate_withCorrectPin_succeeds() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)

            assertSucceeded(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
        }

    @Test
    fun authenticate_withIncorrectPin_fails() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)

            assertFailed(underTest.authenticate(listOf(9, 8, 7, 6, 5, 4)))
        }

    @Test(expected = IllegalArgumentException::class)
    fun authenticate_withEmptyPin_throwsException() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            underTest.authenticate(listOf())
        }

    @Test
    fun authenticate_withCorrectMaxLengthPin_succeeds() =
        testScope.runTest {
            val correctMaxLengthPin = List(16) { 9 }
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                overrideCredential(correctMaxLengthPin)
            }

            assertSucceeded(underTest.authenticate(correctMaxLengthPin))
        }

    @Test
    fun authenticate_withCorrectTooLongPin_fails() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures that
            // all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)

            assertFailed(underTest.authenticate(List(17) { 9 }))
        }

    @Test
    fun authenticate_withCorrectPassword_succeeds() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Password)

            assertSucceeded(underTest.authenticate("password".toList()))
        }

    @Test
    fun authenticate_withIncorrectPassword_fails() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Password)

            assertFailed(underTest.authenticate("alohomora".toList()))
        }

    @Test
    fun authenticate_withCorrectPattern_succeeds() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pattern)

            assertSucceeded(underTest.authenticate(FakeAuthenticationRepository.PATTERN))
        }

    @Test
    fun authenticate_withIncorrectPattern_fails() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pattern)
            val wrongPattern =
                listOf(
                    AuthenticationPatternCoordinate(x = 2, y = 0),
                    AuthenticationPatternCoordinate(x = 2, y = 1),
                    AuthenticationPatternCoordinate(x = 2, y = 2),
                    AuthenticationPatternCoordinate(x = 1, y = 2),
                )

            assertFailed(underTest.authenticate(wrongPattern))
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmPinAndShorterPin_returnsNull() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()
            val defaultPin = FakeAuthenticationRepository.DEFAULT_PIN.toMutableList()

            assertSkipped(
                underTest.authenticate(
                    defaultPin.subList(0, defaultPin.size - 1),
                    tryAutoConfirm = true
                )
            )
            assertThat(underTest.lockoutEndTimestamp).isNull()
            assertThat(kosmos.fakeAuthenticationRepository.lockoutStartedReportCount).isEqualTo(0)
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmWrongPinCorrectLength_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            val wrongPin = FakeAuthenticationRepository.DEFAULT_PIN.map { it + 1 }

            assertFailed(
                underTest.authenticate(wrongPin, tryAutoConfirm = true),
            )
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmLongerPin_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            val longerPin = FakeAuthenticationRepository.DEFAULT_PIN + listOf(7)

            assertFailed(
                underTest.authenticate(longerPin, tryAutoConfirm = true),
            )
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPin_returnsTrue() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
            }
            assertThat(isAutoConfirmEnabled).isTrue()

            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN

            assertSucceeded(underTest.authenticate(correctPin, tryAutoConfirm = true))
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPinButDuringLockout_returnsNull() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
                reportLockoutStarted(42)
            }

            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN

            assertSkipped(underTest.authenticate(correctPin, tryAutoConfirm = true))
            assertThat(isAutoConfirmEnabled).isFalse()
            assertThat(hintedPinLength).isNull()
            assertThat(underTest.lockoutEndTimestamp).isNotNull()
        }

    @Test
    fun tryAutoConfirm_withoutAutoConfirmButCorrectPin_returnsNull() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(false)
            }

            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN

            assertSkipped(underTest.authenticate(correctPin, tryAutoConfirm = true))
        }

    @Test
    fun tryAutoConfirm_withoutCorrectPassword_returnsNull() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Password)

            assertSkipped(underTest.authenticate("password".toList(), tryAutoConfirm = true))
        }

    @Test
    fun isAutoConfirmEnabled_featureDisabled_returnsFalse() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.setAutoConfirmFeatureEnabled(false)

            assertThat(isAutoConfirmEnabled).isFalse()
        }

    @Test
    fun isAutoConfirmEnabled_featureEnabled_returnsTrue() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.setAutoConfirmFeatureEnabled(true)

            assertThat(isAutoConfirmEnabled).isTrue()
        }

    @Test
    fun isAutoConfirmEnabled_featureEnabledButDisabledByLockout() =
        testScope.runTest {
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)
            kosmos.fakeAuthenticationRepository.setAutoConfirmFeatureEnabled(true)

            // The feature is enabled.
            assertThat(isAutoConfirmEnabled).isTrue()

            // Make many wrong attempts to trigger lockout.
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                assertFailed(underTest.authenticate(listOf(5, 6, 7))) // Wrong PIN
            }
            assertThat(underTest.lockoutEndTimestamp).isNotNull()
            assertThat(kosmos.fakeAuthenticationRepository.lockoutStartedReportCount).isEqualTo(1)

            // Lockout disabled auto-confirm.
            assertThat(isAutoConfirmEnabled).isFalse()

            // Move the clock forward one more second, to completely finish the lockout period:
            advanceTimeBy(
                FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS.seconds.plus(1.seconds)
            )
            assertThat(underTest.lockoutEndTimestamp).isNull()

            // Auto-confirm is still disabled, because lockout occurred at least once in this
            // session.
            assertThat(isAutoConfirmEnabled).isFalse()

            // Correct PIN and unlocks successfully, resetting the 'session'.
            assertSucceeded(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))

            // Auto-confirm is re-enabled.
            assertThat(isAutoConfirmEnabled).isTrue()
        }

    @Test
    fun failedAuthenticationAttempts() =
        testScope.runTest {
            val failedAuthenticationAttempts by
                collectLastValue(underTest.failedAuthenticationAttempts)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN

            assertSucceeded(underTest.authenticate(correctPin))
            assertThat(failedAuthenticationAttempts).isEqualTo(0)

            // Make many wrong attempts, leading to lockout:
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) { index ->
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
                assertThat(failedAuthenticationAttempts).isEqualTo(index + 1)
            }

            // Correct PIN, but locked out, so doesn't attempt it:
            assertSkipped(underTest.authenticate(correctPin), assertNoResultEvents = false)
            assertThat(failedAuthenticationAttempts)
                .isEqualTo(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT)

            // Move the clock forward to finish the lockout period:
            advanceTimeBy(FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS.seconds)
            assertThat(failedAuthenticationAttempts)
                .isEqualTo(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT)

            // Correct PIN and no longer locked out so unlocks successfully:
            assertSucceeded(underTest.authenticate(correctPin))
            assertThat(failedAuthenticationAttempts).isEqualTo(0)
        }

    @Test
    fun lockoutEndTimestamp() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN

            underTest.authenticate(correctPin)
            assertThat(underTest.lockoutEndTimestamp).isNull()

            // Make many wrong attempts, but just shy of what's needed to get locked out:
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT - 1) {
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
                assertThat(underTest.lockoutEndTimestamp).isNull()
            }

            // Make one more wrong attempt, leading to lockout:
            underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN

            val expectedLockoutEndTimestamp =
                testScope.currentTime + FakeAuthenticationRepository.LOCKOUT_DURATION_MS
            assertThat(underTest.lockoutEndTimestamp).isEqualTo(expectedLockoutEndTimestamp)
            assertThat(kosmos.fakeAuthenticationRepository.lockoutStartedReportCount).isEqualTo(1)

            // Correct PIN, but locked out, so doesn't attempt it:
            assertSkipped(underTest.authenticate(correctPin), assertNoResultEvents = false)
            assertThat(underTest.lockoutEndTimestamp).isEqualTo(expectedLockoutEndTimestamp)

            // Move the clock forward to ALMOST skip the lockout, leaving one second to go:
            repeat(FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS - 1) {
                advanceTimeBy(1.seconds)
                assertThat(underTest.lockoutEndTimestamp).isEqualTo(expectedLockoutEndTimestamp)
            }

            // Move the clock forward one more second, to completely finish the lockout period:
            advanceTimeBy(1.seconds)
            assertThat(underTest.lockoutEndTimestamp).isNull()

            // Correct PIN and no longer locked out so unlocks successfully:
            assertSucceeded(underTest.authenticate(correctPin))
            assertThat(underTest.lockoutEndTimestamp).isNull()
        }

    @Test
    @EnableFlags(DevicePolicyFlags.FLAG_HEADLESS_SINGLE_USER_FIXES)
    fun upcomingWipe() =
        testScope.runTest {
            val upcomingWipe by collectLastValue(underTest.upcomingWipe)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            val correctPin = FakeAuthenticationRepository.DEFAULT_PIN
            val wrongPin = FakeAuthenticationRepository.DEFAULT_PIN.map { it + 1 }
            kosmos.fakeUserRepository.asMainUser()
            kosmos.fakeAuthenticationRepository.profileWithMinFailedUnlockAttemptsForWipe =
                FakeUserRepository.MAIN_USER_ID

            underTest.authenticate(correctPin)
            assertThat(upcomingWipe).isNull()

            var expectedFailedAttempts = 0
            var remainingFailedAttempts =
                kosmos.fakeAuthenticationRepository.getMaxFailedUnlockAttemptsForWipe()
            assertThat(remainingFailedAttempts)
                .isGreaterThan(LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE)

            // Make many wrong attempts, until wipe is triggered:
            repeat(remainingFailedAttempts) { attemptIndex ->
                underTest.authenticate(wrongPin)
                expectedFailedAttempts++
                remainingFailedAttempts--
                if (underTest.lockoutEndTimestamp != null) {
                    // If there's a lockout, wait it out:
                    advanceTimeBy(FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS.seconds)
                }

                if (attemptIndex < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
                    // No risk of wipe.
                    assertThat(upcomingWipe).isNull()
                } else {
                    // Wipe grace period started; Make additional wrong attempts, confirm the
                    // warning is shown each time:
                    assertThat(upcomingWipe)
                        .isEqualTo(
                            AuthenticationWipeModel(
                                wipeTarget = AuthenticationWipeModel.WipeTarget.WholeDevice,
                                failedAttempts = expectedFailedAttempts,
                                remainingAttempts = remainingFailedAttempts
                            )
                        )
                }
            }

            // Unlock successfully, no more risk of upcoming wipe:
            assertSucceeded(underTest.authenticate(correctPin))
            assertThat(upcomingWipe).isNull()
        }

    @Test
    fun hintedPinLength_withoutAutoConfirm_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(false)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinTooShort_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                overrideCredential(
                    buildList {
                        repeat(kosmos.fakeAuthenticationRepository.hintedPinLength - 1) {
                            add(it + 1)
                        }
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
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                setAutoConfirmFeatureEnabled(true)
                overrideCredential(
                    buildList {
                        repeat(kosmos.fakeAuthenticationRepository.hintedPinLength) { add(it + 1) }
                    }
                )
            }

            assertThat(hintedPinLength)
                .isEqualTo(kosmos.fakeAuthenticationRepository.hintedPinLength)
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinTooLong_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            kosmos.fakeAuthenticationRepository.apply {
                setAuthenticationMethod(Pin)
                overrideCredential(
                    buildList {
                        repeat(kosmos.fakeAuthenticationRepository.hintedPinLength + 1) {
                            add(it + 1)
                        }
                    }
                )
                setAutoConfirmFeatureEnabled(true)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun authenticate_withTooShortPassword() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Password)

            val tooShortPassword = buildList {
                repeat(kosmos.fakeAuthenticationRepository.minPasswordLength - 1) { time ->
                    add("$time")
                }
            }
            assertSkipped(underTest.authenticate(tooShortPassword))
        }

    private fun assertSucceeded(authenticationResult: AuthenticationResult) {
        assertThat(authenticationResult).isEqualTo(AuthenticationResult.SUCCEEDED)
        assertThat(onAuthenticationResult).isTrue()
        assertThat(underTest.lockoutEndTimestamp).isNull()
        assertThat(kosmos.fakeAuthenticationRepository.lockoutStartedReportCount).isEqualTo(0)
        assertThat(failedAuthenticationAttempts).isEqualTo(0)
    }

    private fun assertFailed(
        authenticationResult: AuthenticationResult,
    ) {
        assertThat(authenticationResult).isEqualTo(AuthenticationResult.FAILED)
        assertThat(onAuthenticationResult).isFalse()
    }

    private fun assertSkipped(
        authenticationResult: AuthenticationResult,
        assertNoResultEvents: Boolean = true,
    ) {
        assertThat(authenticationResult).isEqualTo(AuthenticationResult.SKIPPED)
        if (assertNoResultEvents) {
            assertThat(onAuthenticationResult).isNull()
        } else {
            assertThat(onAuthenticationResult).isNotNull()
        }
    }
}
