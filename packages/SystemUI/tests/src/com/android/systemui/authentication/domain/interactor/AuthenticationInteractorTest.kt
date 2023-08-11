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
import com.android.systemui.authentication.data.model.AuthenticationMethodModel as DataLayerAuthenticationMethodModel
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel as DomainLayerAuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    private val sceneInteractor = utils.sceneInteractor()
    private val underTest =
        utils.authenticationInteractor(
            repository = repository,
            sceneInteractor = sceneInteractor,
        )

    @Test
    fun authenticationMethod() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()
            assertThat(authMethod).isEqualTo(DomainLayerAuthenticationMethodModel.Pin)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(DomainLayerAuthenticationMethodModel.Pin)

            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Password
            )

            assertThat(authMethod).isEqualTo(DomainLayerAuthenticationMethodModel.Password)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(DomainLayerAuthenticationMethodModel.Password)
        }

    @Test
    fun authenticationMethod_noneTreatedAsSwipe_whenLockscreenEnabled() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()

            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(true)
            }

            assertThat(authMethod).isEqualTo(DomainLayerAuthenticationMethodModel.Swipe)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(DomainLayerAuthenticationMethodModel.Swipe)
        }

    @Test
    fun authenticationMethod_none_whenLockscreenDisabled() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()

            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(false)
            }

            assertThat(authMethod).isEqualTo(DomainLayerAuthenticationMethodModel.None)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(DomainLayerAuthenticationMethodModel.None)
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenDisabled_isTrue() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(false)
                // Toggle isUnlocked, twice.
                //
                // This is done because the underTest.isUnlocked flow doesn't receive values from
                // just changing the state above; the actual isUnlocked state needs to change to
                // cause the logic under test to "pick up" the current state again.
                //
                // It is done twice to make sure that we don't actually change the isUnlocked state
                // from what it originally was.
                setUnlocked(!utils.authenticationRepository.isUnlocked.value)
                runCurrent()
                setUnlocked(!utils.authenticationRepository.isUnlocked.value)
                runCurrent()
            }

            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenEnabled_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(true)
            }

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun canSwipeToDismiss_onLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(true)
            }
            switchToScene(SceneKey.Lockscreen)

            val canSwipeToDismiss by collectLastValue(underTest.canSwipeToDismiss)
            assertThat(canSwipeToDismiss).isTrue()
        }

    @Test
    fun canSwipeToDismiss_onLockscreenWithPin_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setLockscreenEnabled(true)
            }
            switchToScene(SceneKey.Lockscreen)

            val canSwipeToDismiss by collectLastValue(underTest.canSwipeToDismiss)
            assertThat(canSwipeToDismiss).isFalse()
        }

    @Test
    fun canSwipeToDismiss_afterLockscreenDismissedInSwipeMode_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
                setLockscreenEnabled(true)
            }
            switchToScene(SceneKey.Lockscreen)
            switchToScene(SceneKey.Gone)

            val canSwipeToDismiss by collectLastValue(underTest.canSwipeToDismiss)
            assertThat(canSwipeToDismiss).isFalse()
        }

    @Test
    fun isAuthenticationRequired_lockedAndSecured_true() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setUnlocked(false)
                runCurrent()
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Password)
            }

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setUnlocked(false)
                runCurrent()
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
            }

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setUnlocked(true)
                runCurrent()
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Password)
            }

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            utils.authenticationRepository.apply {
                setUnlocked(true)
                runCurrent()
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.None)
            }

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun authenticate_withCorrectPin_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pin
            )
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)).isTrue()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPin_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pin
            )
            assertThat(underTest.authenticate(listOf(9, 8, 7, 6, 5, 4))).isFalse()
        }

    @Test(expected = IllegalArgumentException::class)
    fun authenticate_withEmptyPin_throwsException() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pin
            )
            underTest.authenticate(listOf())
        }

    @Test
    fun authenticate_withCorrectMaxLengthPin_returnsTrue() =
        testScope.runTest {
            val pin = List(16) { 9 }
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                overrideCredential(pin)
            }

            assertThat(underTest.authenticate(pin)).isTrue()
        }

    @Test
    fun authenticate_withCorrectTooLongPin_returnsFalse() =
        testScope.runTest {
            // Max pin length is 16 digits. To avoid issues with overflows, this test ensures
            // that all pins > 16 decimal digits are rejected.

            // If the policy changes, there is work to do in SysUI.
            assertThat(DevicePolicyManager.MAX_PASSWORD_LENGTH).isLessThan(17)

            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pin
            )
            assertThat(underTest.authenticate(List(17) { 9 })).isFalse()
        }

    @Test
    fun authenticate_withCorrectPassword_returnsTrue() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("password".toList())).isTrue()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun authenticate_withIncorrectPassword_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Password
            )

            assertThat(underTest.authenticate("alohomora".toList())).isFalse()
        }

    @Test
    fun authenticate_withCorrectPattern_returnsTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pattern
            )

            assertThat(underTest.authenticate(FakeAuthenticationRepository.PATTERN)).isTrue()
        }

    @Test
    fun authenticate_withIncorrectPattern_returnsFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pattern
            )

            assertThat(
                    underTest.authenticate(
                        listOf(
                            AuthenticationPatternCoordinate(
                                x = 2,
                                y = 0,
                            ),
                            AuthenticationPatternCoordinate(
                                x = 2,
                                y = 1,
                            ),
                            AuthenticationPatternCoordinate(
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
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(true)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN.toMutableList().apply {
                            removeLast()
                        },
                        tryAutoConfirm = true
                    )
                )
                .isNull()
            assertThat(isThrottled).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmWrongPinCorrectLength_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(true)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN.map { it + 1 },
                        tryAutoConfirm = true
                    )
                )
                .isFalse()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmLongerPin_returnsFalseAndDoesNotUnlockDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(true)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN + listOf(7),
                        tryAutoConfirm = true
                    )
                )
                .isFalse()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withAutoConfirmCorrectPin_returnsTrueAndUnlocksDevice() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(true)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isTrue()
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun tryAutoConfirm_withoutAutoConfirmButCorrectPin_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(false)
            }
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isNull()
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun tryAutoConfirm_withoutCorrectPassword_returnsNullAndHasNoEffects() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Password
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
            utils.authenticationRepository.setAuthenticationMethod(
                DataLayerAuthenticationMethodModel.Pin
            )
            underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
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
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)).isNull()
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
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)).isTrue()
            assertThat(isUnlocked).isTrue()
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
        }

    @Test
    fun hintedPinLength_withoutAutoConfirm_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(false)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinTooShort_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                overrideCredential(
                    buildList {
                        repeat(utils.authenticationRepository.hintedPinLength - 1) { add(it + 1) }
                    }
                )
                setAutoConfirmEnabled(true)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinAtRightLength_isSameLength() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.apply {
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                setAutoConfirmEnabled(true)
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
                setAuthenticationMethod(DataLayerAuthenticationMethodModel.Pin)
                overrideCredential(
                    buildList {
                        repeat(utils.authenticationRepository.hintedPinLength + 1) { add(it + 1) }
                    }
                )
                setAutoConfirmEnabled(true)
            }

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun isLockscreenDismissed() =
        testScope.runTest {
            val isLockscreenDismissed by collectLastValue(underTest.isLockscreenDismissed)
            // Start on lockscreen.
            switchToScene(SceneKey.Lockscreen)
            assertThat(isLockscreenDismissed).isFalse()

            // The user swipes down to reveal shade.
            switchToScene(SceneKey.Shade)
            assertThat(isLockscreenDismissed).isFalse()

            // The user swipes down to reveal quick settings.
            switchToScene(SceneKey.QuickSettings)
            assertThat(isLockscreenDismissed).isFalse()

            // The user swipes up to go back to shade.
            switchToScene(SceneKey.Shade)
            assertThat(isLockscreenDismissed).isFalse()

            // The user swipes up to reveal bouncer.
            switchToScene(SceneKey.Bouncer)
            assertThat(isLockscreenDismissed).isFalse()

            // The user hits back to return to lockscreen.
            switchToScene(SceneKey.Lockscreen)
            assertThat(isLockscreenDismissed).isFalse()

            // The user swipes up to reveal bouncer.
            switchToScene(SceneKey.Bouncer)
            assertThat(isLockscreenDismissed).isFalse()

            // The user enters correct credentials and goes to gone.
            switchToScene(SceneKey.Gone)
            assertThat(isLockscreenDismissed).isTrue()

            // The user swipes down to reveal shade.
            switchToScene(SceneKey.Shade)
            assertThat(isLockscreenDismissed).isTrue()

            // The user swipes down to reveal quick settings.
            switchToScene(SceneKey.QuickSettings)
            assertThat(isLockscreenDismissed).isTrue()

            // The user swipes up to go back to shade.
            switchToScene(SceneKey.Shade)
            assertThat(isLockscreenDismissed).isTrue()

            // The user swipes up to go back to gone.
            switchToScene(SceneKey.Gone)
            assertThat(isLockscreenDismissed).isTrue()

            // The device goes to sleep, returning to the lockscreen.
            switchToScene(SceneKey.Lockscreen)
            assertThat(isLockscreenDismissed).isFalse()
        }

    private fun TestScope.switchToScene(sceneKey: SceneKey) {
        val model = SceneModel(sceneKey)
        val loggingReason = "reason"
        sceneInteractor.changeScene(model, loggingReason)
        sceneInteractor.onSceneChanged(model, loggingReason)
        runCurrent()
    }
}
