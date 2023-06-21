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
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AuthMethodBouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val authenticationInteractor =
        utils.authenticationInteractor(
            utils.authenticationRepository(),
        )
    private val underTest =
        PinBouncerViewModel(
            applicationScope = testScope.backgroundScope,
            interactor =
                utils.bouncerInteractor(
                    authenticationInteractor = authenticationInteractor,
                    sceneInteractor = utils.sceneInteractor(),
                ),
            isInputEnabled = MutableStateFlow(true),
        )

    @Test
    fun animateFailure() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin(1234)
            )
            val animateFailure by collectLastValue(underTest.animateFailure)
            assertThat(animateFailure).isFalse()

            // Wrong PIN:
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onPinButtonClicked(5)
            underTest.onPinButtonClicked(6)
            underTest.onAuthenticateButtonClicked()
            assertThat(animateFailure).isTrue()

            underTest.onFailureAnimationShown()
            assertThat(animateFailure).isFalse()

            // Correct PIN:
            underTest.onPinButtonClicked(1)
            underTest.onPinButtonClicked(2)
            underTest.onPinButtonClicked(3)
            underTest.onPinButtonClicked(4)
            underTest.onAuthenticateButtonClicked()
            assertThat(animateFailure).isFalse()
        }
}
