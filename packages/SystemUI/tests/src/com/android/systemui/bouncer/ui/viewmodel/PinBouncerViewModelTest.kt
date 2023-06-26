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
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PinBouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = utils.authenticationRepository(),
        )
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val bouncerViewModel =
        BouncerViewModel(
            applicationContext = context,
            applicationScope = testScope.backgroundScope,
            interactorFactory =
                object : BouncerInteractor.Factory {
                    override fun create(containerName: String): BouncerInteractor {
                        return bouncerInteractor
                    }
                },
            containerName = CONTAINER_NAME,
        )
    private val underTest =
        PinBouncerViewModel(
            applicationScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pin, ENTER_YOUR_PIN)
        overrideResource(R.string.kg_wrong_pin, WRONG_PIN)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message?.text).isEqualTo(ENTER_YOUR_PIN)
            assertThat(entries).hasSize(0)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onPinButtonClicked() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            runCurrent()

            underTest.onPinButtonClicked(1)

            assertThat(message?.text).isEmpty()
            assertThat(entries).hasSize(1)
            assertThat(entries?.map { it.input }).containsExactly(1)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onBackspaceButtonClicked() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            runCurrent()
            underTest.onPinButtonClicked(1)
            assertThat(entries).hasSize(1)

            underTest.onBackspaceButtonClicked()

            assertThat(message?.text).isEmpty()
            assertThat(entries).hasSize(0)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onPinEdit() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()

            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onBackspaceButtonClicked()
            underTest.onBackspaceButtonClicked()
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5)

            assertThat(entries).hasSize(3)
            assertThat(entries?.map { it.input }).containsExactly(1, 4, 5).inOrder()
            assertThat(entries?.map { it.sequenceNumber }).isInStrictOrder()
        }

    @Test
    fun onBackspaceButtonLongPressed() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            runCurrent()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)

            underTest.onBackspaceButtonLongPressed()

            assertThat(message?.text).isEmpty()
            assertThat(entries).hasSize(0)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_whenCorrect() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)

            underTest.onAuthenticateButtonClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onAuthenticateButtonClicked_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!

            underTest.onAuthenticateButtonClicked()

            assertThat(entries).hasSize(0)
            assertThat(message?.text).isEqualTo(WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_correctAfterWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!
            underTest.onAuthenticateButtonClicked()
            assertThat(message?.text).isEqualTo(WRONG_PIN)
            assertThat(entries).hasSize(0)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Enter the correct PIN:
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            assertThat(message?.text).isEmpty()

            underTest.onAuthenticateButtonClicked()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onAutoConfirm_whenCorrect() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onAutoConfirm_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val entries by collectLastValue(underTest.pinEntries)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(5) // PIN is now wrong!

            assertThat(entries).hasSize(0)
            assertThat(message?.text).isEqualTo(WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun backspaceButtonAppearance_withoutAutoConfirm_alwaysShown() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = false)
            )

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Shown)
        }

    @Test
    fun backspaceButtonAppearance_withAutoConfirmButNoInput_isHidden() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Hidden)
        }

    @Test
    fun backspaceButtonAppearance_withAutoConfirmAndInput_isShownQuiet() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            underTest.onPinButtonClicked(1)

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Subtle)
        }

    @Test
    fun confirmButtonAppearance_withoutAutoConfirm_alwaysShown() =
        testScope.runTest {
            val confirmButtonAppearance by collectLastValue(underTest.confirmButtonAppearance)

            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = false)
            )

            assertThat(confirmButtonAppearance).isEqualTo(ActionButtonAppearance.Shown)
        }

    @Test
    fun confirmButtonAppearance_withAutoConfirm_isHidden() =
        testScope.runTest {
            val confirmButtonAppearance by collectLastValue(underTest.confirmButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = true)
            )

            assertThat(confirmButtonAppearance).isEqualTo(ActionButtonAppearance.Hidden)
        }

    @Test
    fun hintedPinLength_withoutAutoConfirm_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234, autoConfirm = false)
            )

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinLessThanSixDigits_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(12345, autoConfirm = true)
            )

            assertThat(hintedPinLength).isNull()
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinExactlySixDigits_isSix() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(123456, autoConfirm = true)
            )

            assertThat(hintedPinLength).isEqualTo(6)
        }

    @Test
    fun hintedPinLength_withAutoConfirmPinMoreThanSixDigits_isNull() =
        testScope.runTest {
            val hintedPinLength by collectLastValue(underTest.hintedPinLength)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234567, autoConfirm = true)
            )

            assertThat(hintedPinLength).isNull()
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
        private const val ENTER_YOUR_PIN = "Enter your pin"
        private const val WRONG_PIN = "Wrong pin"
    }
}
