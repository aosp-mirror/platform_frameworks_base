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

package com.android.systemui.touchpad.tutorial.ui.viewmodel

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.inputDeviceTutorialLogger
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.tutorial.ui.gesture.EasterEggGesture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EasterEggGestureViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val viewModel =
        EasterEggGestureViewModel(
            GestureRecognizerAdapter(
                EasterEggRecognizerProvider(),
                kosmos.inputDeviceTutorialLogger,
            )
        )

    @Before
    fun before() {
        kosmos.useUnconfinedTestDispatcher()
    }

    @Test
    fun easterEggNotTriggeredAtStart() =
        kosmos.runTest {
            val easterEggTriggered by collectLastValue(viewModel.easterEggTriggered)
            assertThat(easterEggTriggered).isFalse()
        }

    @Test
    fun emitsTrueOnEasterEggTriggered() =
        kosmos.runTest {
            assertStateAfterEvents(
                events = EasterEggGesture.motionEventsForGesture(),
                expected = true,
            )
        }

    @Test
    fun emitsFalseOnEasterEggCallbackExecuted() =
        kosmos.runTest {
            val easterEggTriggered by collectLastValue(viewModel.easterEggTriggered)
            EasterEggGesture.motionEventsForGesture().forEach { viewModel.accept(it) }

            assertThat(easterEggTriggered).isEqualTo(true)
            viewModel.onEasterEggFinished()
            assertThat(easterEggTriggered).isEqualTo(false)
        }

    private fun Kosmos.assertStateAfterEvents(events: List<MotionEvent>, expected: Boolean) {
        val state by collectLastValue(viewModel.easterEggTriggered)
        events.forEach { viewModel.accept(it) }
        assertThat(state).isEqualTo(expected)
    }
}
