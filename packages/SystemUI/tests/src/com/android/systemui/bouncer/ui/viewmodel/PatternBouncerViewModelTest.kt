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
import com.android.systemui.authentication.data.repository.AuthenticationRepositoryImpl
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repo.BouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PatternBouncerViewModelTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val sceneInteractor =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    private val mAuthenticationInteractor =
        AuthenticationInteractor(
            applicationScope = testScope.backgroundScope,
            repository = AuthenticationRepositoryImpl(),
        )
    private val bouncerInteractor =
        BouncerInteractor(
            applicationScope = testScope.backgroundScope,
            applicationContext = context,
            repository = BouncerRepository(),
            authenticationInteractor = mAuthenticationInteractor,
            sceneInteractor = sceneInteractor,
            containerName = CONTAINER_NAME,
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
        PatternBouncerViewModel(
            applicationContext = context,
            applicationScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
        )

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pattern, ENTER_YOUR_PATTERN)
        overrideResource(R.string.kg_wrong_pattern, WRONG_PATTERN)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))

            underTest.onShown()

            assertThat(message).isEqualTo(ENTER_YOUR_PATTERN)
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragStart() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
            underTest.onShown()

            underTest.onDragStart()

            assertThat(message).isEmpty()
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragEnd_whenCorrect() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
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

            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Gone))
        }

    @Test
    fun onDragEnd_whenWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
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
            assertThat(message).isEqualTo(WRONG_PATTERN)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Bouncer))
        }

    @Test
    fun onDragEnd_correctAfterWrong() =
        testScope.runTest {
            val isUnlocked by collectLastValue(mAuthenticationInteractor.isUnlocked)
            val currentScene by collectLastValue(sceneInteractor.currentScene(CONTAINER_NAME))
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            mAuthenticationInteractor.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern(CORRECT_PATTERN)
            )
            mAuthenticationInteractor.lockDevice()
            sceneInteractor.setCurrentScene(CONTAINER_NAME, SceneModel(SceneKey.Bouncer))
            assertThat(isUnlocked).isFalse()
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
            assertThat(message).isEqualTo(WRONG_PATTERN)
            assertThat(isUnlocked).isFalse()
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

            assertThat(isUnlocked).isTrue()
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
