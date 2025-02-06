/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.content.res.mockResources
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.inputdevice.tutorial.inputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Error
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgress
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.tutorial.ui.gesture.FourFingerGesture
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.android.systemui.touchpad.ui.gesture.touchpadGestureResources
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SwitchAppsGestureScreenViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val resources = kosmos.mockResources
    private val fakeConfigRepository = kosmos.fakeConfigurationRepository
    private val viewModel =
        SwitchAppsGestureScreenViewModel(
            GestureRecognizerAdapter(
                SwitchAppsGestureRecognizerProvider(kosmos.touchpadGestureResources),
                kosmos.inputDeviceTutorialLogger,
            )
        )

    @Before
    fun before() {
        setDistanceThreshold(threshold = SWIPE_DISTANCE - 1)
        kosmos.useUnconfinedTestDispatcher()
    }

    @Test
    fun emitsProgressStateWithProgressAnimation() =
        kosmos.runTest {
            assertProgressWhileMovingFingers(
                deltaX = SWIPE_DISTANCE,
                expected =
                    InProgress(
                        progress = 1f,
                        startMarker = "gesture to R",
                        endMarker = "end of gesture",
                    ),
            )
        }

    @Test
    fun emitsFinishedStateWithSuccessAnimation() =
        kosmos.runTest {
            assertStateAfterEvents(
                events = FourFingerGesture.swipeRight(),
                expected = Finished(successAnimation = R.raw.trackpad_switch_apps_success),
            )
        }

    @Test
    fun emitErrorStateWhenLatestDistanceThresholdNotReached() =
        kosmos.runTest {
            fun performGesture() =
                FourFingerGesture.swipeRight().forEach { viewModel.handleEvent(it) }
            val state by collectLastValue(viewModel.tutorialState)
            performGesture()
            assertThat(state).isInstanceOf(Finished::class.java)

            setDistanceThreshold(SWIPE_DISTANCE + 1)
            performGesture() // now swipe distance is not enough to trigger success

            assertThat(state).isInstanceOf(Error::class.java)
        }

    private fun setDistanceThreshold(threshold: Float) {
        whenever(
                resources.getDimensionPixelSize(
                    R.dimen.touchpad_tutorial_gestures_distance_threshold
                )
            )
            .thenReturn(threshold.toInt())
        fakeConfigRepository.onAnyConfigurationChange()
    }

    private fun Kosmos.assertProgressWhileMovingFingers(
        deltaX: Float,
        expected: TutorialActionState,
    ) {
        assertStateAfterEvents(
            events = FourFingerGesture.eventsForGestureInProgress { move(deltaX = deltaX) },
            expected = expected,
        )
    }

    private fun Kosmos.assertStateAfterEvents(
        events: List<MotionEvent>,
        expected: TutorialActionState,
    ) {
        val state by collectLastValue(viewModel.tutorialState)
        events.forEach { viewModel.handleEvent(it) }
        assertThat(state).isEqualTo(expected)
    }
}
