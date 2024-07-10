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
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT
import android.view.MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE
import android.view.MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@Ignore
@SmallTest
@RunWith(AndroidJUnit4::class)
class BackGestureMonitorTest : SysuiTestCase() {

    private var gestureDoneWasCalled = false
    private val gestureDoneCallback = { gestureDoneWasCalled = true }
    private val gestureMonitor = BackGestureMonitor(SWIPE_DISTANCE.toInt(), gestureDoneCallback)

    companion object {
        const val SWIPE_DISTANCE = 100f
    }

    @Test
    fun triggersGestureDoneForThreeFingerGestureRight() {
        val events =
            listOf(
                threeFingerEvent(ACTION_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_MOVE, x = SWIPE_DISTANCE / 2, y = 0f),
                threeFingerEvent(ACTION_POINTER_UP, x = SWIPE_DISTANCE, y = 0f),
                threeFingerEvent(ACTION_POINTER_UP, x = SWIPE_DISTANCE, y = 0f),
                threeFingerEvent(ACTION_UP, x = SWIPE_DISTANCE, y = 0f),
            )

        events.forEach { gestureMonitor.processTouchpadEvent(it) }

        assertThat(gestureDoneWasCalled).isTrue()
    }

    @Test
    fun triggersGestureDoneForThreeFingerGestureLeft() {
        val events =
            listOf(
                threeFingerEvent(ACTION_DOWN, x = SWIPE_DISTANCE, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = SWIPE_DISTANCE, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = SWIPE_DISTANCE, y = 0f),
                threeFingerEvent(ACTION_MOVE, x = SWIPE_DISTANCE / 2, y = 0f),
                threeFingerEvent(ACTION_POINTER_UP, x = 0f, y = 0f),
                threeFingerEvent(ACTION_POINTER_UP, x = 0f, y = 0f),
                threeFingerEvent(ACTION_UP, x = 0f, y = 0f),
            )

        events.forEach { gestureMonitor.processTouchpadEvent(it) }

        assertThat(gestureDoneWasCalled).isTrue()
    }

    private fun threeFingerEvent(action: Int, x: Float, y: Float): MotionEvent {
        return motionEvent(
            action = action,
            x = x,
            y = y,
            classification = CLASSIFICATION_MULTI_FINGER_SWIPE,
            axisValues = mapOf(AXIS_GESTURE_SWIPE_FINGER_COUNT to 3f)
        )
    }

    @Test
    fun doesntTriggerGestureDone_onThreeFingersSwipeUp() {
        val events =
            listOf(
                threeFingerEvent(ACTION_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                threeFingerEvent(ACTION_MOVE, x = 0f, y = SWIPE_DISTANCE / 2),
                threeFingerEvent(ACTION_POINTER_UP, x = 0f, y = SWIPE_DISTANCE),
                threeFingerEvent(ACTION_POINTER_UP, x = 0f, y = SWIPE_DISTANCE),
                threeFingerEvent(ACTION_UP, x = 0f, y = SWIPE_DISTANCE),
            )

        events.forEach { gestureMonitor.processTouchpadEvent(it) }

        assertThat(gestureDoneWasCalled).isFalse()
    }

    @Test
    fun doesntTriggerGestureDone_onTwoFingersSwipe() {
        fun twoFingerEvent(action: Int, x: Float, y: Float) =
            motionEvent(
                action = action,
                x = x,
                y = y,
                classification = CLASSIFICATION_TWO_FINGER_SWIPE,
                axisValues = mapOf(AXIS_GESTURE_SWIPE_FINGER_COUNT to 2f)
            )
        val events =
            listOf(
                twoFingerEvent(ACTION_DOWN, x = 0f, y = 0f),
                twoFingerEvent(ACTION_MOVE, x = SWIPE_DISTANCE / 2, y = 0f),
                twoFingerEvent(ACTION_UP, x = SWIPE_DISTANCE, y = 0f),
            )

        events.forEach { gestureMonitor.processTouchpadEvent(it) }

        assertThat(gestureDoneWasCalled).isFalse()
    }

    @Test
    fun doesntTriggerGestureDone_onFourFingersSwipe() {
        fun fourFingerEvent(action: Int, x: Float, y: Float) =
            motionEvent(
                action = action,
                x = x,
                y = y,
                classification = CLASSIFICATION_MULTI_FINGER_SWIPE,
                axisValues = mapOf(AXIS_GESTURE_SWIPE_FINGER_COUNT to 4f)
            )
        val events =
            listOf(
                fourFingerEvent(ACTION_DOWN, x = 0f, y = 0f),
                fourFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                fourFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                fourFingerEvent(ACTION_POINTER_DOWN, x = 0f, y = 0f),
                fourFingerEvent(ACTION_MOVE, x = SWIPE_DISTANCE / 2, y = 0f),
                fourFingerEvent(ACTION_POINTER_UP, x = SWIPE_DISTANCE, y = 0f),
                fourFingerEvent(ACTION_POINTER_UP, x = SWIPE_DISTANCE, y = 0f),
                fourFingerEvent(ACTION_POINTER_UP, x = SWIPE_DISTANCE, y = 0f),
                fourFingerEvent(ACTION_UP, x = SWIPE_DISTANCE, y = 0f),
            )

        events.forEach { gestureMonitor.processTouchpadEvent(it) }

        assertThat(gestureDoneWasCalled).isFalse()
    }
}
