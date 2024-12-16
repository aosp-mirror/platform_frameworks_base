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

package com.android.systemui.touchpad.tutorial.ui.gesture

import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.Error
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.Finished
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NotStarted
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.android.systemui.touchpad.tutorial.ui.gesture.RecentAppsGestureRecognizerTest.Companion.FAST
import com.android.systemui.touchpad.tutorial.ui.gesture.RecentAppsGestureRecognizerTest.Companion.SLOW
import com.android.systemui.touchpad.tutorial.ui.gesture.RecentAppsGestureRecognizerTest.Companion.THRESHOLD_VELOCITY_PX_PER_MS
import com.android.systemui.touchpad.ui.gesture.FakeVelocityTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ThreeFingerGestureRecognizerTest(
    private val recognizer: GestureRecognizer,
    private val validGestures: Set<List<MotionEvent>>,
    private val tooShortGesture: List<MotionEvent>,
    @Suppress("UNUSED_PARAMETER") testSuffix: String, // here just for nicer test names
) : SysuiTestCase() {

    private var gestureState: GestureState = GestureState.NotStarted

    @Before
    fun before() {
        recognizer.addGestureStateCallback { gestureState = it }
    }

    @Test
    fun triggersGestureFinishedForValidGestures() {
        validGestures.forEach { assertStateAfterEvents(events = it, expectedState = Finished) }
    }

    @Test
    fun triggersGestureProgressForThreeFingerGestureStarted() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.startEvents(x = 0f, y = 0f),
            expectedState = InProgress(progress = 0f),
        )
    }

    @Test
    fun triggersGestureError_onGestureDistanceTooShort() {
        assertStateAfterEvents(events = tooShortGesture, expectedState = Error)
    }

    @Test
    fun triggersGestureError_onThreeFingersSwipeInOtherDirections() {
        val allThreeFingerGestures =
            listOf(
                ThreeFingerGesture.swipeUp(),
                ThreeFingerGesture.swipeDown(),
                ThreeFingerGesture.swipeLeft(),
                ThreeFingerGesture.swipeRight(),
            )
        val invalidGestures = allThreeFingerGestures.filter { it.differentFromAnyOf(validGestures) }
        invalidGestures.forEach { assertStateAfterEvents(events = it, expectedState = Error) }
    }

    @Test
    fun triggersGestureError_onTwoFingersSwipe() {
        assertStateAfterEvents(events = TwoFingerGesture.swipeRight(), expectedState = Error)
    }

    @Test
    fun doesntTriggerGestureError_TwoFingerSwipeInProgress() {
        assertStateAfterEvents(
            events = TwoFingerGesture.eventsForGestureInProgress { move(deltaX = SWIPE_DISTANCE) },
            expectedState = NotStarted,
        )
    }

    @Test
    fun triggersGestureError_onFourFingersSwipe() {
        assertStateAfterEvents(events = FourFingerGesture.swipeRight(), expectedState = Error)
    }

    @Test
    fun doesntTriggerGestureError_FourFingerSwipeInProgress() {
        assertStateAfterEvents(
            events = FourFingerGesture.eventsForGestureInProgress { move(deltaX = SWIPE_DISTANCE) },
            expectedState = NotStarted,
        )
    }

    @Test
    fun ignoresOneFingerSwipes() {
        val oneFingerSwipe =
            listOf(
                touchpadEvent(MotionEvent.ACTION_DOWN, 50f, 50f),
                touchpadEvent(MotionEvent.ACTION_MOVE, 55f, 55f),
                touchpadEvent(MotionEvent.ACTION_UP, 60f, 60f),
            )
        assertStateAfterEvents(events = oneFingerSwipe, expectedState = NotStarted)
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, expectedState: GestureState) {
        events.forEach { recognizer.accept(it) }
        assertThat(gestureState).isEqualTo(expectedState)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{3}")
        fun gesturesToTest(): List<Array<Any>> =
            with(ThreeFingerGesture) {
                listOf(
                        GestureTestData(
                            recognizer = BackGestureRecognizer(SWIPE_DISTANCE.toInt()),
                            validGestures = setOf(swipeRight(), swipeLeft()),
                            tooShortGesture = swipeRight(SWIPE_DISTANCE / 2),
                            testSuffix = "back gesture",
                        ),
                        GestureTestData(
                            recognizer =
                                HomeGestureRecognizer(
                                    SWIPE_DISTANCE.toInt(),
                                    THRESHOLD_VELOCITY_PX_PER_MS,
                                    FakeVelocityTracker(velocity = FAST),
                                ),
                            validGestures = setOf(swipeUp()),
                            tooShortGesture = swipeUp(SWIPE_DISTANCE / 2),
                            testSuffix = "home gesture",
                        ),
                        GestureTestData(
                            recognizer =
                                RecentAppsGestureRecognizer(
                                    SWIPE_DISTANCE.toInt(),
                                    THRESHOLD_VELOCITY_PX_PER_MS,
                                    FakeVelocityTracker(velocity = SLOW),
                                ),
                            validGestures = setOf(swipeUp()),
                            tooShortGesture = swipeUp(SWIPE_DISTANCE / 2),
                            testSuffix = "recent apps gesture",
                        ),
                    )
                    .map {
                        arrayOf(it.recognizer, it.validGestures, it.tooShortGesture, it.testSuffix)
                    }
            }
    }

    class GestureTestData(
        val recognizer: GestureRecognizer,
        val validGestures: Set<List<MotionEvent>>,
        val tooShortGesture: List<MotionEvent>,
        val testSuffix: String,
    )
}

private fun List<MotionEvent>.differentFromAnyOf(validGestures: Set<List<MotionEvent>>): Boolean {
    // comparing MotionEvents is really hard so let's just compare their positions
    val positions = this.map { it.x to it.y }
    val validGesturesPositions = validGestures.map { gesture -> gesture.map { it.x to it.y } }
    return !validGesturesPositions.contains(positions)
}
