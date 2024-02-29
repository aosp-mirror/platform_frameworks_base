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
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.authenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate as Point
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class PatternBouncerViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val bouncerInteractor by lazy { kosmos.bouncerInteractor }
    private val bouncerViewModel by lazy { kosmos.bouncerViewModel }
    private val underTest by lazy {
        PatternBouncerViewModel(
            applicationContext = context,
            viewModelScope = testScope.backgroundScope,
            interactor = bouncerInteractor,
            isInputEnabled = MutableStateFlow(true).asStateFlow(),
        )
    }

    private val containerSize = 90 // px
    private val dotSize = 30 // px

    @Before
    fun setUp() {
        overrideResource(R.string.keyguard_enter_your_pattern, ENTER_YOUR_PATTERN)
        overrideResource(R.string.kg_wrong_pattern, WRONG_PATTERN)
    }

    @Test
    fun onShown() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            lockDeviceAndOpenPatternBouncer()

            assertThat(message?.text).isEqualTo(ENTER_YOUR_PATTERN)
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
            assertThat(underTest.authenticationMethod).isEqualTo(AuthenticationMethodModel.Pattern)
        }

    @Test
    fun onDragStart() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            lockDeviceAndOpenPatternBouncer()

            underTest.onDragStart()

            assertThat(message?.text).isEmpty()
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
        }

    @Test
    fun onDragEnd_whenCorrect() =
        testScope.runTest {
            val authResult by collectLastValue(authenticationInteractor.onAuthenticationResult)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            lockDeviceAndOpenPatternBouncer()
            underTest.onDragStart()
            assertThat(currentDot).isNull()
            CORRECT_PATTERN.forEachIndexed { index, coordinate ->
                dragToCoordinate(coordinate)
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

            assertThat(authResult).isTrue()
        }

    @Test
    fun onDragEnd_whenWrong() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            lockDeviceAndOpenPatternBouncer()
            underTest.onDragStart()
            CORRECT_PATTERN.subList(0, 3).forEach { coordinate -> dragToCoordinate(coordinate) }

            underTest.onDragEnd()

            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(message?.text).isEqualTo(WRONG_PATTERN)
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
        }

    @Test
    fun onDrag_shouldIncludeDotsThatWereSkippedOverAlongTheSameRow() =
        testScope.runTest {
            val selectedDots by collectLastValue(underTest.selectedDots)
            lockDeviceAndOpenPatternBouncer()

            /*
             * Pattern setup, coordinates are (column, row)
             *   0  1  2
             * 0 x  x  x
             * 1 x  x  x
             * 2 x  x  x
             */
            // Select (0,0), Skip over (1,0) and select (2,0)
            dragOverCoordinates(Point(0, 0), Point(2, 0))

            assertThat(selectedDots)
                .isEqualTo(
                    listOf(
                        PatternDotViewModel(0, 0),
                        PatternDotViewModel(1, 0),
                        PatternDotViewModel(2, 0)
                    )
                )
        }

    @Test
    fun onDrag_shouldIncludeDotsThatWereSkippedOverAlongTheSameColumn() =
        testScope.runTest {
            val selectedDots by collectLastValue(underTest.selectedDots)
            lockDeviceAndOpenPatternBouncer()

            /*
             * Pattern setup, coordinates are (column, row)
             *   0  1  2
             * 0 x  x  x
             * 1 x  x  x
             * 2 x  x  x
             */
            // Select (1,0), Skip over (1,1) and select (1, 2)
            dragOverCoordinates(Point(1, 0), Point(1, 2))

            assertThat(selectedDots)
                .isEqualTo(
                    listOf(
                        PatternDotViewModel(1, 0),
                        PatternDotViewModel(1, 1),
                        PatternDotViewModel(1, 2)
                    )
                )
        }

    @Test
    fun onDrag_shouldIncludeDotsThatWereSkippedOverAlongTheDiagonal() =
        testScope.runTest {
            val selectedDots by collectLastValue(underTest.selectedDots)
            lockDeviceAndOpenPatternBouncer()

            /*
             * Pattern setup
             *   0  1  2
             * 0 x  x  x
             * 1 x  x  x
             * 2 x  x  x
             *
             * Coordinates are (column, row)
             * Select (2,0), Skip over (1,1) and select (0, 2)
             */
            dragOverCoordinates(Point(2, 0), Point(0, 2))

            assertThat(selectedDots)
                .isEqualTo(
                    listOf(
                        PatternDotViewModel(2, 0),
                        PatternDotViewModel(1, 1),
                        PatternDotViewModel(0, 2)
                    )
                )
        }

    @Test
    fun onDrag_shouldNotIncludeDotIfItIsNotOnTheLine() =
        testScope.runTest {
            val selectedDots by collectLastValue(underTest.selectedDots)
            lockDeviceAndOpenPatternBouncer()

            /*
             * Pattern setup
             *   0  1  2
             * 0 x  x  x
             * 1 x  x  x
             * 2 x  x  x
             *
             * Coordinates are (column, row)
             */
            dragOverCoordinates(Point(0, 0), Point(1, 0), Point(2, 0), Point(0, 1))

            assertThat(selectedDots)
                .isEqualTo(
                    listOf(
                        PatternDotViewModel(0, 0),
                        PatternDotViewModel(1, 0),
                        PatternDotViewModel(2, 0),
                        PatternDotViewModel(0, 1),
                    )
                )
        }

    @Test
    fun onDrag_shouldNotIncludeSkippedOverDotsIfTheyAreAlreadySelected() =
        testScope.runTest {
            val selectedDots by collectLastValue(underTest.selectedDots)
            lockDeviceAndOpenPatternBouncer()

            /*
             * Pattern setup
             *   0  1  2
             * 0 x  x  x
             * 1 x  x  x
             * 2 x  x  x
             *
             * Coordinates are (column, row)
             */
            dragOverCoordinates(Point(1, 0), Point(1, 1), Point(0, 0), Point(2, 0))

            assertThat(selectedDots)
                .isEqualTo(
                    listOf(
                        PatternDotViewModel(1, 0),
                        PatternDotViewModel(1, 1),
                        PatternDotViewModel(0, 0),
                        PatternDotViewModel(2, 0),
                    )
                )
        }

    @Test
    fun onDragEnd_whenPatternTooShort() =
        testScope.runTest {
            val message by collectLastValue(bouncerViewModel.message)
            val dialogViewModel by collectLastValue(bouncerViewModel.dialogViewModel)
            lockDeviceAndOpenPatternBouncer()

            // Enter a pattern that's too short more than enough times that would normally trigger
            // lockout if the pattern were not too short and wrong:
            val attempts = FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT + 1
            repeat(attempts) { attempt ->
                underTest.onDragStart()
                CORRECT_PATTERN.subList(
                        0,
                        kosmos.authenticationRepository.minPatternLength - 1,
                    )
                    .forEach { coordinate ->
                        underTest.onDrag(
                            xPx = 30f * coordinate.x + 15,
                            yPx = 30f * coordinate.y + 15,
                            containerSizePx = 90,
                        )
                    }

                underTest.onDragEnd()

                assertWithMessage("Attempt #$attempt").that(message?.text).isEqualTo(WRONG_PATTERN)
                assertWithMessage("Attempt #$attempt").that(dialogViewModel).isNull()
            }
        }

    @Test
    fun onDragEnd_correctAfterWrong() =
        testScope.runTest {
            val authResult by collectLastValue(authenticationInteractor.onAuthenticationResult)
            val message by collectLastValue(bouncerViewModel.message)
            val selectedDots by collectLastValue(underTest.selectedDots)
            val currentDot by collectLastValue(underTest.currentDot)
            lockDeviceAndOpenPatternBouncer()

            underTest.onDragStart()
            CORRECT_PATTERN.subList(2, 7).forEach(::dragToCoordinate)
            underTest.onDragEnd()
            assertThat(selectedDots).isEmpty()
            assertThat(currentDot).isNull()
            assertThat(message?.text).isEqualTo(WRONG_PATTERN)
            assertThat(authResult).isFalse()

            // Enter the correct pattern:
            CORRECT_PATTERN.forEach(::dragToCoordinate)

            underTest.onDragEnd()

            assertThat(authResult).isTrue()
        }

    private fun dragOverCoordinates(vararg coordinatesDragged: Point) {
        underTest.onDragStart()
        coordinatesDragged.forEach(::dragToCoordinate)
    }

    private fun dragToCoordinate(coordinate: Point) {
        underTest.onDrag(
            xPx = dotSize * coordinate.x + 15f,
            yPx = dotSize * coordinate.y + 15f,
            containerSizePx = containerSize,
        )
    }

    private fun TestScope.switchToScene(toScene: SceneKey) {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        val bouncerShown = currentScene != Scenes.Bouncer && toScene == Scenes.Bouncer
        val bouncerHidden = currentScene == Scenes.Bouncer && toScene != Scenes.Bouncer
        sceneInteractor.changeScene(toScene, "reason")
        if (bouncerShown) underTest.onShown()
        if (bouncerHidden) underTest.onHidden()
        runCurrent()

        assertThat(currentScene).isEqualTo(toScene)
    }

    private fun TestScope.lockDeviceAndOpenPatternBouncer() {
        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
            AuthenticationMethodModel.Pattern
        )
        kosmos.fakeDeviceEntryRepository.setUnlocked(false)
        switchToScene(Scenes.Bouncer)
    }

    companion object {
        private const val ENTER_YOUR_PATTERN = "Enter your pattern"
        private const val WRONG_PATTERN = "Wrong pattern"
        private val CORRECT_PATTERN = FakeAuthenticationRepository.PATTERN
    }
}
