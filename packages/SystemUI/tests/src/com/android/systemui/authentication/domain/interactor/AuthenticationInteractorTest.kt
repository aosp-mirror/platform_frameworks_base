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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
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
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AuthenticationInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val repository: AuthenticationRepository = utils.authenticationRepository()
    private val underTest =
        utils.authenticationInteractor(
            repository = repository,
        )

    @Test
    fun getAuthenticationMethod() =
        testScope.runTest {
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(AuthenticationMethodModel.Pin)

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Password)
        }

    @Test
    fun getAuthenticationMethod_noneTreatedAsSwipe_whenLockscreenEnabled() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.authenticationRepository.setLockscreenEnabled(true)

            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Swipe)
        }

    @Test
    fun getAuthenticationMethod_none_whenLockscreenDisabled() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.authenticationRepository.setLockscreenEnabled(false)

            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.None)
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenDisabled_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.authenticationRepository.setLockscreenEnabled(false)

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenEnabled_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.authenticationRepository.setLockscreenEnabled(true)

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun toggleBypassEnabled() =
        testScope.runTest {
            val isBypassEnabled by collectLastValue(underTest.isBypassEnabled)
            assertThat(isBypassEnabled).isFalse()

            underTest.toggleBypassEnabled()
            assertThat(isBypassEnabled).isTrue()

            underTest.toggleBypassEnabled()
            assertThat(isBypassEnabled).isFalse()
        }

    @Test
    fun isAuthenticationRequired_lockedAndSecured_true() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Swipe)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun authenticate_withCorrectPin_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4))).isTrue()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPin_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(listOf(9, 8, 7))).isFalse()
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
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            val pin = List(16) { 9 }
            utils.authenticationRepository.overrideCredential(pin)
            assertThat(underTest.authenticate(pin)).isTrue()
        }

    @Test
    fun authenticate_withCorrectTooLongPin_returnsFalse() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures
            // that all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(underTest.authenticate(List(17) { 9 })).isFalse()
        }

    @Test
    fun authenticate_withCorrectPassword_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList())).isTrue()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPassword_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("alohomora".toList())).isFalse()
        }

    @Test
    fun authenticate_withCorrectPattern_returnsTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )

            assertThat(underTest.authenticate(FakeAuthenticationRepository.PATTERN)).isTrue()
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
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 0,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 1,
                            ),
                            AuthenticationMethodModel.Pattern.PatternCoordinate(
                                x = 2,
                                y = 2,
                            ),
                        )
                    )
                )
                .isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmPinAndShorterPin_returnsNullAndHasNoEffect() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmEnabled(true)
            assertThat(underTest.authenticate(listOf(1, 2, 3), tryAutoConfirm = true)).isNull()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmWrongPinCorrectLength_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmEnabled(true)
            assertThat(underTest.authenticate(listOf(1, 2, 4, 4), tryAutoConfirm = true)).isFalse()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmLongerPin_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmEnabled(true)
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4, 5), tryAutoConfirm = true))
                .isFalse()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPin_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmEnabled(true)
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4), tryAutoConfirm = true)).isTrue()
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun tryAutoConfirm_withoutAutoConfirmButCorrectPin_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmEnabled(false)
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4), tryAutoConfirm = true)).isNull()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withoutCorrectPassword_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList(), tryAutoConfirm = true)).isNull()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun throttling() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            val throttling by collectLastValue(underTest.throttling)
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            underTest.authenticate(listOf(1, 2, 3, 4))
            assertThat(isUnlocked).isTrue()
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())

            utils.authenticationRepository.setUnlocked(false)
            assertThat(isUnlocked).isFalse()
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())

            // Make many wrong attempts, but just shy of what's needed to get throttled:
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING - 1) {
                underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
                assertThat(isUnlocked).isFalse()
                assertThat(isThrottled).isFalse()
                assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
            }

            // Make one more wrong attempt, leading to throttling:
            underTest.authenticate(listOf(5, 6, 7)) // Wrong PIN
            assertThat(isUnlocked).isFalse()
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
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4))).isNull()
            assertThat(isUnlocked).isFalse()
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
            assertThat(isUnlocked).isFalse()
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
            assertThat(underTest.authenticate(listOf(1, 2, 3, 4))).isTrue()
            assertThat(isUnlocked).isTrue()
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
        }
}
