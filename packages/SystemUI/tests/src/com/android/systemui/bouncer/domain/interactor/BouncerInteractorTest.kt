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

package com.android.systemui.bouncer.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.res.R
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BouncerInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val authenticationInteractor = utils.authenticationInteractor()
    private val sceneInteractor = utils.sceneInteractor()
    private val deviceEntryInteractor =
        utils.deviceEntryInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val underTest =
        utils.bouncerInteractor(
            deviceEntryInteractor = deviceEntryInteractor,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pin, MESSAGE_ENTER_YOUR_PIN)
        overrideResource(R.string.keyguard_enter_your_password, MESSAGE_ENTER_YOUR_PASSWORD)
        overrideResource(R.string.keyguard_enter_your_pattern, MESSAGE_ENTER_YOUR_PATTERN)
        overrideResource(R.string.kg_wrong_pin, MESSAGE_WRONG_PIN)
        overrideResource(R.string.kg_wrong_password, MESSAGE_WRONG_PASSWORD)
        overrideResource(R.string.kg_wrong_pattern, MESSAGE_WRONG_PATTERN)
    }

    @Test
    fun pinAuthMethod() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            runCurrent()
            utils.deviceEntryRepository.setUnlocked(false)
            underTest.showOrUnlockDevice()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            underTest.clearMessage()
            assertThat(message).isEmpty()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            // Wrong input.
            assertThat(underTest.authenticate(listOf(9, 8, 7)))
                .isEqualTo(AuthenticationResult.FAILED)
            assertThat(message).isEqualTo(MESSAGE_WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            // Correct input.
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun pinAuthMethod_tryAutoConfirm_withAutoConfirmPin() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)
            val isAutoConfirmEnabled by collectLastValue(underTest.isAutoConfirmEnabled)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            runCurrent()
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)
            utils.deviceEntryRepository.setUnlocked(false)
            underTest.showOrUnlockDevice()
            assertThat(isAutoConfirmEnabled).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)
            underTest.clearMessage()

            // Incomplete input.
            assertThat(underTest.authenticate(listOf(1, 2), tryAutoConfirm = true))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(message).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Wrong 6-digit pin
            assertThat(underTest.authenticate(listOf(1, 2, 3, 5, 5, 6), tryAutoConfirm = true))
                .isEqualTo(AuthenticationResult.FAILED)
            assertThat(message).isEqualTo(MESSAGE_WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Correct input.
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun pinAuthMethod_tryAutoConfirm_withoutAutoConfirmPin() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            runCurrent()
            utils.deviceEntryRepository.setUnlocked(false)
            underTest.showOrUnlockDevice()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.clearMessage()

            // Incomplete input.
            assertThat(underTest.authenticate(listOf(1, 2), tryAutoConfirm = true))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(message).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Correct input.
            assertThat(
                    underTest.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN,
                        tryAutoConfirm = true
                    )
                )
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(message).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun passwordAuthMethod() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            runCurrent()
            utils.deviceEntryRepository.setUnlocked(false)
            underTest.showOrUnlockDevice()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            underTest.clearMessage()
            assertThat(message).isEmpty()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            // Wrong input.
            assertThat(underTest.authenticate("alohamora".toList()))
                .isEqualTo(AuthenticationResult.FAILED)
            assertThat(message).isEqualTo(MESSAGE_WRONG_PASSWORD)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            // Correct input.
            assertThat(underTest.authenticate("password".toList()))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun patternAuthMethod() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )
            runCurrent()
            utils.deviceEntryRepository.setUnlocked(false)
            underTest.showOrUnlockDevice()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            underTest.clearMessage()
            assertThat(message).isEmpty()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            // Wrong input.
            val wrongPattern =
                listOf(
                    AuthenticationPatternCoordinate(1, 2),
                    AuthenticationPatternCoordinate(1, 1),
                    AuthenticationPatternCoordinate(0, 0),
                    AuthenticationPatternCoordinate(0, 1),
                )
            assertThat(wrongPattern).isNotEqualTo(FakeAuthenticationRepository.PATTERN)
            assertThat(wrongPattern.size).isAtLeast(utils.authenticationRepository.minPatternLength)
            assertThat(underTest.authenticate(wrongPattern)).isEqualTo(AuthenticationResult.FAILED)
            assertThat(message).isEqualTo(MESSAGE_WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            // Too short input.
            val tooShortPattern =
                FakeAuthenticationRepository.PATTERN.subList(
                    0,
                    utils.authenticationRepository.minPatternLength - 1
                )
            assertThat(underTest.authenticate(tooShortPattern))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(message).isEqualTo(MESSAGE_WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            // Correct input.
            assertThat(underTest.authenticate(FakeAuthenticationRepository.PATTERN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_notLocked_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.deviceEntryRepository.setUnlocked(true)
            runCurrent()

            underTest.showOrUnlockDevice()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_authMethodNotSecure_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            utils.deviceEntryRepository.setUnlocked(false)

            underTest.showOrUnlockDevice()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_customMessageShown() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(underTest.message)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            runCurrent()
            utils.deviceEntryRepository.setUnlocked(false)

            val customMessage = "Hello there!"
            underTest.showOrUnlockDevice(customMessage)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(customMessage)
        }

    @Test
    fun throttling() =
        testScope.runTest {
            val isThrottled by collectLastValue(underTest.isThrottled)
            val throttling by collectLastValue(underTest.throttling)
            val message by collectLastValue(underTest.message)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            runCurrent()
            underTest.showOrUnlockDevice()
            runCurrent()
            assertThat(currentScene?.key).isEqualTo(SceneKey.Bouncer)
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)
            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING) { times ->
                // Wrong PIN.
                assertThat(underTest.authenticate(listOf(6, 7, 8, 9)))
                    .isEqualTo(AuthenticationResult.FAILED)
                if (
                    times < FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING - 1
                ) {
                    assertThat(message).isEqualTo(MESSAGE_WRONG_PIN)
                }
            }
            assertThat(isThrottled).isTrue()
            assertThat(throttling)
                .isEqualTo(
                    AuthenticationThrottlingModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                        remainingMs = FakeAuthenticationRepository.THROTTLE_DURATION_MS,
                    )
                )
            assertTryAgainMessage(
                message,
                FakeAuthenticationRepository.THROTTLE_DURATION_MS.milliseconds.inWholeSeconds
                    .toInt()
            )

            // Correct PIN, but throttled, so doesn't change away from the bouncer scene:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SKIPPED)
            assertThat(currentScene?.key).isEqualTo(SceneKey.Bouncer)
            assertTryAgainMessage(
                message,
                FakeAuthenticationRepository.THROTTLE_DURATION_MS.milliseconds.inWholeSeconds
                    .toInt()
            )

            throttling?.remainingMs?.let { remainingMs ->
                val seconds = ceil(remainingMs / 1000f).toInt()
                repeat(seconds) { time ->
                    advanceTimeBy(1000)
                    val remainingTimeSec = seconds - time - 1
                    if (remainingTimeSec > 0) {
                        assertTryAgainMessage(message, remainingTimeSec)
                    }
                }
            }
            assertThat(message).isEqualTo("")
            assertThat(isThrottled).isFalse()
            assertThat(throttling)
                .isEqualTo(
                    AuthenticationThrottlingModel(
                        failedAttemptCount =
                            FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_THROTTLING,
                    )
                )
            assertThat(currentScene?.key).isEqualTo(SceneKey.Bouncer)

            // Correct PIN and no longer throttled so changes to the Gone scene:
            assertThat(underTest.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
                .isEqualTo(AuthenticationResult.SUCCEEDED)
            assertThat(currentScene?.key).isEqualTo(SceneKey.Gone)
            assertThat(isThrottled).isFalse()
            assertThat(throttling).isEqualTo(AuthenticationThrottlingModel())
        }

    @Test
    fun hide_whenOnBouncerScene_hidesBouncerAndGoesToLockscreenScene() =
        testScope.runTest {
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer), "")
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val bouncerSceneKey = currentScene?.key
            assertThat(bouncerSceneKey).isEqualTo(SceneKey.Bouncer)

            underTest.onImeHidden()

            assertThat(currentScene?.key).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun hide_whenNotOnBouncerScene_doesNothing() =
        testScope.runTest {
            sceneInteractor.changeScene(SceneModel(SceneKey.Shade), "")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Shade), "")
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val notBouncerSceneKey = currentScene?.key
            assertThat(notBouncerSceneKey).isNotEqualTo(SceneKey.Bouncer)

            underTest.onImeHidden()

            assertThat(currentScene?.key).isEqualTo(notBouncerSceneKey)
        }

    private fun assertTryAgainMessage(
        message: String?,
        time: Int,
    ) {
        assertThat(message).isEqualTo("Try again in $time seconds.")
    }

    companion object {
        private const val MESSAGE_ENTER_YOUR_PIN = "Enter your PIN"
        private const val MESSAGE_ENTER_YOUR_PASSWORD = "Enter your password"
        private const val MESSAGE_ENTER_YOUR_PATTERN = "Enter your pattern"
        private const val MESSAGE_WRONG_PIN = "Wrong PIN"
        private const val MESSAGE_WRONG_PASSWORD = "Wrong password"
        private const val MESSAGE_WRONG_PATTERN = "Wrong pattern"
    }
}
