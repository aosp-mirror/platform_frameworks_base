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

import android.content.res.mockResources
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.inputdevice.tutorial.inputDeviceTutorialLogger
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.tutorial.ui.composable.GestureUiState
import com.android.systemui.touchpad.tutorial.ui.composable.GestureUiState.Error
import com.android.systemui.touchpad.tutorial.ui.composable.GestureUiState.Finished
import com.android.systemui.touchpad.tutorial.ui.composable.GestureUiState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.android.systemui.touchpad.tutorial.ui.gesture.ThreeFingerGesture
import com.android.systemui.touchpad.tutorial.ui.gesture.Velocity
import com.android.systemui.touchpad.ui.gesture.fakeVelocityTracker
import com.android.systemui.touchpad.ui.gesture.touchpadGestureResources
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeGestureScreenViewModelTest : SysuiTestCase() {

    companion object {
        const val GESTURE_VELOCITY = 1f
        const val LOW_VELOCITY_THRESHOLD = GESTURE_VELOCITY - 0.01f
        const val TOO_HIGH_VELOCITY_THRESHOLD = GESTURE_VELOCITY + 0.01f
    }

    private val kosmos = testKosmos()
    private val fakeConfigRepository = kosmos.fakeConfigurationRepository
    private val fakeVelocityTracker = kosmos.fakeVelocityTracker
    private val resources = kosmos.mockResources

    private val viewModel =
        HomeGestureScreenViewModel(
            GestureRecognizerAdapter(
                HomeGestureRecognizerProvider(kosmos.touchpadGestureResources, fakeVelocityTracker),
                kosmos.inputDeviceTutorialLogger,
            )
        )

    @Before
    fun before() {
        setDistanceThreshold(threshold = SWIPE_DISTANCE - 1)
        setVelocityThreshold(threshold = LOW_VELOCITY_THRESHOLD)
        fakeVelocityTracker.setVelocity(Velocity(GESTURE_VELOCITY))
        kosmos.useUnconfinedTestDispatcher()
    }

    @Test
    fun emitsProgressStateWithAnimationMarkers() =
        kosmos.runTest {
            assertStateAfterEvents(
                events =
                    ThreeFingerGesture.eventsForGestureInProgress {
                        move(deltaY = -SWIPE_DISTANCE)
                    },
                expected =
                    InProgress(
                        progress = 1f,
                        progressStartMarker = "drag with gesture",
                        progressEndMarker = "release playback realtime",
                    ),
            )
        }

    @Test
    fun emitsFinishedStateWithSuccessAnimation() =
        kosmos.runTest {
            assertStateAfterEvents(
                events = ThreeFingerGesture.swipeUp(),
                expected = Finished(successAnimation = R.raw.trackpad_home_success),
            )
        }

    private fun performHomeGesture() {
        ThreeFingerGesture.swipeUp().forEach { viewModel.handleEvent(it) }
    }

    @Test
    fun gestureRecognitionTakesLatestDistanceThresholdIntoAccount() =
        kosmos.runTest {
            val state by collectLastValue(viewModel.gestureUiState)
            performHomeGesture()
            assertThat(state).isInstanceOf(Finished::class.java)

            setDistanceThreshold(SWIPE_DISTANCE + 1)
            performHomeGesture() // now swipe distance is not enough to trigger success

            assertThat(state).isInstanceOf(Error::class.java)
        }

    @Test
    fun gestureRecognitionTakesLatestVelocityThresholdIntoAccount() =
        kosmos.runTest {
            val state by collectLastValue(viewModel.gestureUiState)
            performHomeGesture()
            assertThat(state).isInstanceOf(Finished::class.java)

            setVelocityThreshold(TOO_HIGH_VELOCITY_THRESHOLD)
            performHomeGesture()

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

    private fun setVelocityThreshold(threshold: Float) {
        whenever(resources.getDimension(R.dimen.touchpad_home_gesture_velocity_threshold))
            .thenReturn(threshold)
        fakeConfigRepository.onAnyConfigurationChange()
    }

    private fun Kosmos.assertStateAfterEvents(events: List<MotionEvent>, expected: GestureUiState) {
        val state by collectLastValue(viewModel.gestureUiState)
        events.forEach { viewModel.handleEvent(it) }
        assertThat(state).isEqualTo(expected)
    }
}
