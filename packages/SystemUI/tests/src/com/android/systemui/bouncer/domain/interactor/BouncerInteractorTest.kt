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

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepositoryImpl
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repo.BouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class BouncerInteractorTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val authenticationInteractor =
        AuthenticationInteractor(
            applicationScope = testScope.backgroundScope,
            repository = AuthenticationRepositoryImpl(),
        )
    private val sceneInteractor =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    private val underTest =
        BouncerInteractor(
            applicationScope = testScope.backgroundScope,
            applicationContext = context,
            repository = BouncerRepository(),
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            containerName = "container1",
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
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            val message by collectLastValue(underTest.message)

            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.lockDevice()
            underTest.showOrUnlockDevice("container1")
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            underTest.clearMessage()
            assertThat(message).isNull()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            // Wrong input.
            underTest.authenticate(listOf(9, 8, 7))
            assertThat(message).isEqualTo(MESSAGE_WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PIN)

            // Correct input.
            underTest.authenticate(listOf(1, 2, 3, 4))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun passwordAuthMethod() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            val message by collectLastValue(underTest.message)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            underTest.showOrUnlockDevice("container1")
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            underTest.clearMessage()
            assertThat(message).isNull()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            // Wrong input.
            underTest.authenticate("alohamora".toList())
            assertThat(message).isEqualTo(MESSAGE_WRONG_PASSWORD)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PASSWORD)

            // Correct input.
            underTest.authenticate("password".toList())
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun patternAuthMethod() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            val message by collectLastValue(underTest.message)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(emptyList())
            )
            authenticationInteractor.lockDevice()
            underTest.showOrUnlockDevice("container1")
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            underTest.clearMessage()
            assertThat(message).isNull()

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            // Wrong input.
            underTest.authenticate(
                listOf(AuthenticationMethodModel.Pattern.PatternCoordinate(3, 4))
            )
            assertThat(message).isEqualTo(MESSAGE_WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.resetMessage()
            assertThat(message).isEqualTo(MESSAGE_ENTER_YOUR_PATTERN)

            // Correct input.
            underTest.authenticate(emptyList())
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_notLocked_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.PIN(1234))
            authenticationInteractor.unlockDevice()
            runCurrent()

            underTest.showOrUnlockDevice("container1")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_authMethodNotSecure_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            authenticationInteractor.setAuthenticationMethod(AuthenticationMethodModel.Swipe)
            authenticationInteractor.lockDevice()

            underTest.showOrUnlockDevice("container1")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun showOrUnlockDevice_customMessageShown() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene("container1"))
            val message by collectLastValue(underTest.message)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()

            val customMessage = "Hello there!"
            underTest.showOrUnlockDevice("container1", customMessage)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(message).isEqualTo(customMessage)
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
