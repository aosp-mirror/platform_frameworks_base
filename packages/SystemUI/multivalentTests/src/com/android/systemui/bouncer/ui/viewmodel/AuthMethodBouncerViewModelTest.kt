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
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AuthMethodBouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val bouncerInteractor =
        utils.bouncerInteractor(
            authenticationInteractor = utils.authenticationInteractor(),
        )
    private val underTest =
        PinBouncerViewModel(
            applicationContext = context,
            viewModelScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true),
            simBouncerInteractor = utils.simBouncerInteractor,
            authenticationMethod = AuthenticationMethodModel.Pin,
        )

    @Test
    fun animateFailure() =
        testScope.runTest {
            val animateFailure by collectLastValue(underTest.animateFailure)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            assertThat(animateFailure).isFalse()

            // Wrong PIN:
            FakeAuthenticationRepository.DEFAULT_PIN.drop(2).forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }
            underTest.onAuthenticateButtonClicked()
            assertThat(animateFailure).isTrue()

            underTest.onFailureAnimationShown()
            assertThat(animateFailure).isFalse()

            // Correct PIN:
            FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
                underTest.onPinButtonClicked(digit)
            }
            underTest.onAuthenticateButtonClicked()
            assertThat(animateFailure).isFalse()
        }
}
