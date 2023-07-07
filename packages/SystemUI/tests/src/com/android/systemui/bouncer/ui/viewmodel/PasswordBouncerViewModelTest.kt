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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PasswordBouncerViewModelTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val utils = SceneTestUtils(this, testScope)
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )
    private val sceneInteractor = utils.sceneInteractor()
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val bouncerViewModel =
        utils.bouncerViewModel(
            bouncerInteractor = bouncerInteractor,
        )
    private val underTest =
        PasswordBouncerViewModel(
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_password, ENTER_YOUR_PASSWORD)
        overrideResource(R.string.kg_wrong_password, WRONG_PASSWORD)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val password by collectLastValue(underTest.password)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message?.text).isEqualTo(ENTER_YOUR_PASSWORD)
            assertThat(password).isEqualTo("")
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onPasswordInputChanged() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val password by collectLastValue(underTest.password)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()

            underTest.onPasswordInputChanged("password")

            assertThat(message?.text).isEmpty()
            assertThat(password).isEqualTo("password")
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateKeyPressed_whenCorrect() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPasswordInputChanged("password")

            underTest.onAuthenticateKeyPressed()

            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onAuthenticateKeyPressed_whenWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val password by collectLastValue(underTest.password)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPasswordInputChanged("wrong")

            underTest.onAuthenticateKeyPressed()

            assertThat(password).isEqualTo("")
            assertThat(message?.text).isEqualTo(WRONG_PASSWORD)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateKeyPressed_correctAfterWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(authenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val password by collectLastValue(underTest.password)
            authenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Password("password")
            )
            authenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPasswordInputChanged("wrong")
            underTest.onAuthenticateKeyPressed()
            assertThat(password).isEqualTo("")
            assertThat(message?.text).isEqualTo(WRONG_PASSWORD)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Enter the correct password:
            underTest.onPasswordInputChanged("password")
            assertThat(message?.text).isEmpty()

            underTest.onAuthenticateKeyPressed()

            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
        private const val ENTER_YOUR_PASSWORD = "Enter your password"
        private const val WRONG_PASSWORD = "Wrong password"
    }
}
