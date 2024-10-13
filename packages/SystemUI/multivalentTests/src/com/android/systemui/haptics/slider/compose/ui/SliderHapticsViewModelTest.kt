/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.haptics.slider.compose.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderEventType
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.sliderHapticsViewModelFactory
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class SliderHapticsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val interactionSource = DragInteractionSourceTest()
    private val underTest =
        kosmos.sliderHapticsViewModelFactory.create(
            interactionSource,
            0f..1f,
            Orientation.Horizontal,
            SliderHapticFeedbackConfig(),
            SeekableSliderTrackerConfig(),
        )

    @Before
    fun setUp() {
        underTest.activateIn(testScope)
    }

    @Test
    fun onActivated_startsRunning() =
        testScope.runTest {
            // WHEN the view-model is activated
            testScope.runCurrent()

            // THEN the view-model starts running
            assertThat(underTest.isRunning).isTrue()
        }

    @Test
    fun onDragStart_goesToUserStartedDragging() =
        testScope.runTest {
            // WHEN a drag interaction starts
            interactionSource.setDragInteraction(DragInteraction.Start())
            runCurrent()

            // THEN the current slider event type shows that the user started dragging
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.STARTED_TRACKING_TOUCH)
        }

    @Test
    fun onValueChange_whileUserStartedDragging_goesToUserDragging() =
        testScope.runTest {
            // WHEN a drag interaction starts
            interactionSource.setDragInteraction(DragInteraction.Start())
            runCurrent()

            // WHEN a value changes in the slider
            underTest.onValueChange(0.5f)

            // THEN the current slider event type shows that the user is dragging
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.PROGRESS_CHANGE_BY_USER)
        }

    @Test
    fun onValueChange_whileUserDragging_staysInUserDragging() =
        testScope.runTest {
            // WHEN a drag interaction starts and the user keeps dragging
            interactionSource.setDragInteraction(DragInteraction.Start())
            runCurrent()
            underTest.onValueChange(0.5f)

            // WHEN value changes continue to occur due to dragging
            underTest.onValueChange(0.6f)

            // THEN the current slider event type reflects that the user continues to drag
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.PROGRESS_CHANGE_BY_USER)
        }

    @Test
    fun onValueChange_whileNOTHING_goesToProgramStartedDragging() =
        testScope.runTest {
            // WHEN a value change occurs without a drag interaction
            underTest.onValueChange(0.5f)

            // THEN the current slider event type shows that the program started dragging
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.STARTED_TRACKING_PROGRAM)
        }

    @Test
    fun onValueChange_whileProgramStartedDragging_goesToProgramDragging() =
        testScope.runTest {
            // WHEN the program starts dragging
            underTest.onValueChange(0.5f)

            // WHEN the program continues to make value changes
            underTest.onValueChange(0.6f)

            // THEN the current slider event type shows that program is dragging
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM)
        }

    @Test
    fun onValueChange_whileProgramDragging_staysInProgramDragging() =
        testScope.runTest {
            // WHEN the program starts and continues to drag
            underTest.onValueChange(0.5f)
            underTest.onValueChange(0.6f)

            // WHEN value changes continue to occur
            underTest.onValueChange(0.7f)

            // THEN the current slider event type shows that the program is dragging the slider
            assertThat(underTest.currentSliderEventType)
                .isEqualTo(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM)
        }

    @Test
    fun onValueChangeEnded_goesToNOTHING() =
        testScope.runTest {
            // WHEN changes end in the slider
            underTest.onValueChangeEnded()

            // THEN the current slider event type always resets to NOTHING
            assertThat(underTest.currentSliderEventType).isEqualTo(SliderEventType.NOTHING)
        }

    private class DragInteractionSourceTest : InteractionSource {
        private val _interactions = MutableStateFlow<DragInteraction>(IdleDrag)
        override val interactions = _interactions.asStateFlow()

        fun setDragInteraction(interaction: DragInteraction) {
            _interactions.value = interaction
        }
    }

    private object IdleDrag : DragInteraction
}
