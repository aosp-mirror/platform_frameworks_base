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
import com.google.common.truth.Truth.assertWithMessage
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
class PatternBouncerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
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
        PatternBouncerViewModel(
            applicationContext = context,
            applicationScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pattern, ENTER_YOUR_PATTERN)
        overrideResource(R.string.kg_wrong_pattern, WRONG_PATTERN)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message?.text).isEqualTo(ENTER_YOUR_PATTERN)
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragStart() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            runCurrent()

            underTest.onDragStart()

            assertThat(message?.text).isEmpty()
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragEnd_whenCorrect() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onDragStart()
            assertThat(currentDot).isNull()
            CORRECT_PATTERN.forEachIndexed { index, coordinate ->
                underTest.onDrag(
                    xPx = 30f * coordinate.x + 15,
                    yPx = 30f * coordinate.y + 15,
                    containerSizePx = 90,
                    verticalOffsetPx = 0f,
                )
                assertWithMessage("Wrong selected dots for index $index")
                    .that(selectedDots)
                    .isEqualTo(
                        CORRECT_PATTERN.subList(0, index + 1).map {
                            PatternDotViewModel(
                                x = it.x,
                                y = it.y,
                            )
                        }
                    )
                assertWithMessage("Wrong current dot for index $index")
                    .that(currentDot)
                    .isEqualTo(
                        PatternDotViewModel(
                            x = CORRECT_PATTERN.subList(0, index + 1).last().x,
                            y = CORRECT_PATTERN.subList(0, index + 1).last().y,
                        )
                    )
            }

            underTest.onDragEnd()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onDragEnd_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onDragStart()
            CORRECT_PATTERN.subList(0, 3).forEach { coordinate ->
                underTest.onDrag(
                    xPx = 30f * coordinate.x + 15,
                    yPx = 30f * coordinate.y + 15,
                    containerSizePx = 90,
                    verticalOffsetPx = 0f,
                )
            }

            underTest.onDragEnd()

            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(message?.text).isEqualTo(WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragEnd_correctAfterWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            utils.authenticationRepository.setUnlocked(false)
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()
            underTest.onDragStart()
            CORRECT_PATTERN.subList(2, 7).forEach { coordinate ->
                underTest.onDrag(
                    xPx = 30f * coordinate.x + 15,
                    yPx = 30f * coordinate.y + 15,
                    containerSizePx = 90,
                    verticalOffsetPx = 0f,
                )
            }
            underTest.onDragEnd()
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(message?.text).isEqualTo(WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            // Enter the correct pattern:
            CORRECT_PATTERN.forEach { coordinate ->
                underTest.onDrag(
                    xPx = 30f * coordinate.x + 15,
                    yPx = 30f * coordinate.y + 15,
                    containerSizePx = 90,
                    verticalOffsetPx = 0f,
                )
            }

            underTest.onDragEnd()

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    companion object {
        private const val CONTAINER_NAME = "container1"
        private const val ENTER_YOUR_PATTERN = "Enter your pattern"
        private const val WRONG_PATTERN = "Wrong pattern"
        private val CORRECT_PATTERN =
            listOf(
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 1, y = 1),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 0, y = 1),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 0, y = 0),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 1, y = 0),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 2, y = 0),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 2, y = 1),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 2, y = 2),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 1, y = 2),
                AuthenticationMethodModel.Pattern.PatternCoordinate(x = 0, y = 2),
            )
    }
}
