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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel as DomainAuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.res.R
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PinBouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor = utils.authenticationInteractor()
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )
    private val bouncerViewModel =
        utils.bouncerViewModel(
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            actionButtonInteractor = utils.bouncerActionButtonInteractor(),
        )
    private val underTest =
        PinBouncerViewModel(
            applicationContext = context,
            viewModelScope = testScope.backgroundScope,
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
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            utils.deviceEntryRepository.setUnlocked(false)
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message?.text).ignoringCase().isEqualTo(ENTER_YOUR_PIN)
            assertThat(pin).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            assertThat(underTest.authenticationMethod)
                .isEqualTo(DomainAuthenticationMethodModel.Pin)
        }

    @Test
    fun onPinButtonClicked() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.deviceEntryRepository.setUnlocked(false)
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            runCurrent()

            underTest.onPinButtonClicked(1)

            assertThat(message?.text).isEmpty()
            assertThat(pin).containsExactly(1)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onBackspaceButtonClicked() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            underTest.onPinButtonClicked(1)
            assertThat(pin).hasSize(1)

            underTest.onBackspaceButtonClicked()

            assertThat(message?.text).isEmpty()
            assertThat(pin).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onPinEdit() =
        testScope.runTest {
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onBackspaceButtonClicked()
            underTest.onBackspaceButtonClicked()
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5)

            assertThat(pin).containsExactly(1, 4, 5).inOrder()
        }

    @Test
    fun onBackspaceButtonLongPressed() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            runCurrent()

            underTest.onBackspaceButtonLongPressed()

            assertThat(message?.text).isEmpty()
            assertThat(pin).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_whenCorrect() =
        testScope.runTest {
            val authResult by
                collectLastValue(authenticationInteractor.authenticationChallengeResult)
            lockDeviceAndOpenPinBouncer()

            FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }

            underTest.onAuthenticateButtonClicked()

            assertThat(authResult).isTrue()
        }

    @Test
    fun onAuthenticateButtonClicked_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!

            underTest.onAuthenticateButtonClicked()

            assertThat(pin).isEmpty()
            assertThat(message?.text).ignoringCase().isEqualTo(WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onAuthenticateButtonClicked_correctAfterWrong() =
        testScope.runTest {
            val authResult by
                collectLastValue(authenticationInteractor.authenticationChallengeResult)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5) // PIN is now wrong!
            underTest.onAuthenticateButtonClicked()
            assertThat(message?.text).ignoringCase().isEqualTo(WRONG_PIN)
            assertThat(pin).isEmpty()
            assertThat(authResult).isFalse()

            // Enter the correct PIN:
            FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }
            assertThat(message?.text).isEmpty()

            underTest.onAuthenticateButtonClicked()

            assertThat(authResult).isTrue()
        }

    @Test
    fun onAutoConfirm_whenCorrect() =
        testScope.runTest {
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)
            val authResult by
                collectLastValue(authenticationInteractor.authenticationChallengeResult)
            lockDeviceAndOpenPinBouncer()

            FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }

            assertThat(authResult).isTrue()
        }

    @Test
    fun onAutoConfirm_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val message by collectLastValue(bouncerViewModel.message)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)
            lockDeviceAndOpenPinBouncer()

            FakeAuthenticationRepository.DEFAULT_PIN.dropLast(1).forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }
            underTest.onPinButtonClicked(
                FakeAuthenticationRepository.DEFAULT_PIN.last() + 1
            ) // PIN is now wrong!

            assertThat(pin).isEmpty()
            assertThat(message?.text).ignoringCase().isEqualTo(WRONG_PIN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onShown_againAfterSceneChange_resetsPin() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            val pin by collectLastValue(underTest.pinInput.map { it.getPin() })
            lockDeviceAndOpenPinBouncer()

            // The user types a PIN.
            FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }
            assertThat(pin).isNotEmpty()

            // The user doesn't confirm the PIN, but navigates back to the lockscreen instead.
            sceneInteractor.changeScene(SceneModel(SceneKey.Lockscreen), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))

            // The user navigates to the bouncer again.
            sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "reason")
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer), "reason")
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            // Ensure the previously-entered PIN is not shown.
            assertThat(pin).isEmpty()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun backspaceButtonAppearance_withoutAutoConfirm_alwaysShown() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Shown)
        }

    @Test
    fun backspaceButtonAppearance_withAutoConfirmButNoInput_isHidden() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Hidden)
        }

    @Test
    fun backspaceButtonAppearance_withAutoConfirmAndInput_isShownQuiet() =
        testScope.runTest {
            val backspaceButtonAppearance by collectLastValue(underTest.backspaceButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)

            underTest.onPinButtonClicked(1)

            assertThat(backspaceButtonAppearance).isEqualTo(ActionButtonAppearance.Subtle)
        }

    @Test
    fun confirmButtonAppearance_withoutAutoConfirm_alwaysShown() =
        testScope.runTest {
            val confirmButtonAppearance by collectLastValue(underTest.confirmButtonAppearance)

            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(confirmButtonAppearance).isEqualTo(ActionButtonAppearance.Shown)
        }

    @Test
    fun confirmButtonAppearance_withAutoConfirm_isHidden() =
        testScope.runTest {
            val confirmButtonAppearance by collectLastValue(underTest.confirmButtonAppearance)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.authenticationRepository.setAutoConfirmFeatureEnabled(true)

            assertThat(confirmButtonAppearance).isEqualTo(ActionButtonAppearance.Hidden)
        }

    private fun TestScope.lockDeviceAndOpenPinBouncer() {
        utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
        utils.deviceEntryRepository.setUnlocked(false)
        sceneInteractor.changeScene(SceneModel(SceneKey.Bouncer), "reason")
        sceneInteractor.onSceneChanged(SceneModel(SceneKey.Bouncer), "reason")

        assertThat(collectLastValue(sceneInteractor.desiredScene).invoke())
            .isEqualTo(SceneModel(SceneKey.Bouncer))
        underTest.onShown()
        runCurrent()
    }

    companion object {
        private const val ENTER_YOUR_PIN = "Enter your pin"
        private const val WRONG_PIN = "Wrong pin"
    }
}
