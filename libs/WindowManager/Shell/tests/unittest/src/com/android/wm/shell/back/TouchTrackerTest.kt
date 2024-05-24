/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.wm.shell.back

import android.util.MathUtils
import android.window.BackEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchTrackerTest {
    private fun linearTouchTracker(): TouchTracker = TouchTracker().apply {
        setProgressThresholds(MAX_DISTANCE, MAX_DISTANCE, NON_LINEAR_FACTOR)
    }

    private fun nonLinearTouchTracker(): TouchTracker = TouchTracker().apply {
        setProgressThresholds(LINEAR_DISTANCE, MAX_DISTANCE, NON_LINEAR_FACTOR)
    }

    private fun TouchTracker.assertProgress(expected: Float) {
        val actualProgress = createProgressEvent().progress
        assertEquals(expected, actualProgress, /* delta = */ 0f)
    }

    @Test
    fun generatesProgress_onStart() {
        val linearTracker = linearTouchTracker()
        linearTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0f, BackEvent.EDGE_LEFT)
        val event = linearTracker.createStartEvent(null)
        assertEquals(0f, event.progress, 0f)
    }

    @Test
    fun generatesProgress_leftEdge() {
        val linearTracker = linearTouchTracker()
        linearTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0f, BackEvent.EDGE_LEFT)
        var touchX = 10f
        val velocityX = 0f
        val velocityY = 0f

        // Pre-commit
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / MAX_DISTANCE)

        // Post-commit
        touchX += 100f
        linearTracker.setTriggerBack(true)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / MAX_DISTANCE)

        // Cancel
        touchX -= 10f
        linearTracker.setTriggerBack(false)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // Cancel more
        touchX -= 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // Restarted, but pre-commit
        val restartX = touchX
        touchX += 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - restartX) / MAX_DISTANCE)

        // continue restart within pre-commit
        touchX += 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - restartX) / MAX_DISTANCE)

        // Restarted, post-commit
        touchX += 10f
        linearTracker.setTriggerBack(true)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / MAX_DISTANCE)
    }

    @Test
    fun generatesProgress_rightEdge() {
        val linearTracker = linearTouchTracker()
        linearTracker.setGestureStartLocation(INITIAL_X_RIGHT_EDGE, 0f, BackEvent.EDGE_RIGHT)
        var touchX = INITIAL_X_RIGHT_EDGE - 10 // Fake right edge
        val velocityX = 0f
        val velocityY = 0f
        val target = MAX_DISTANCE

        // Pre-commit
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((INITIAL_X_RIGHT_EDGE - touchX) / target)

        // Post-commit
        touchX -= 100f
        linearTracker.setTriggerBack(true)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((INITIAL_X_RIGHT_EDGE - touchX) / target)

        // Cancel
        touchX += 10f
        linearTracker.setTriggerBack(false)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // Cancel more
        touchX += 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // Restarted, but pre-commit
        val restartX = touchX
        touchX -= 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((restartX - touchX) / target)

        // continue restart within pre-commit
        touchX -= 10f
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((restartX - touchX) / target)

        // Restarted, post-commit
        touchX -= 10f
        linearTracker.setTriggerBack(true)
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((INITIAL_X_RIGHT_EDGE - touchX) / target)
    }

    @Test
    fun generatesNonLinearProgress_leftEdge() {
        val nonLinearTracker = nonLinearTouchTracker()
        nonLinearTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0f, BackEvent.EDGE_LEFT)
        var touchX = 10f
        val velocityX = 0f
        val velocityY = 0f
        val linearTarget = LINEAR_DISTANCE + (MAX_DISTANCE - LINEAR_DISTANCE) * NON_LINEAR_FACTOR

        // Pre-commit: linear progress
        nonLinearTracker.update(touchX, 0f, velocityX, velocityY)
        nonLinearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / linearTarget)

        // Post-commit: still linear progress
        touchX += 100f
        nonLinearTracker.setTriggerBack(true)
        nonLinearTracker.update(touchX, 0f, velocityX, velocityY)
        nonLinearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / linearTarget)

        // still linear progress
        touchX = INITIAL_X_LEFT_EDGE + LINEAR_DISTANCE
        nonLinearTracker.update(touchX, 0f, velocityX, velocityY)
        nonLinearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / linearTarget)

        // non linear progress
        touchX += 10
        nonLinearTracker.update(touchX, 0f, velocityX, velocityY)
        val nonLinearTouch = (touchX - INITIAL_X_LEFT_EDGE) - LINEAR_DISTANCE
        val nonLinearProgress = nonLinearTouch / NON_LINEAR_DISTANCE
        val nonLinearTarget = MathUtils.lerp(linearTarget, MAX_DISTANCE, nonLinearProgress)
        nonLinearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / nonLinearTarget)
    }

    @Test
    fun restartingGesture_resetsInitialTouchX_leftEdge() {
        val linearTracker = linearTouchTracker()
        linearTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0f, BackEvent.EDGE_LEFT)
        var touchX = 100f
        val velocityX = 0f
        val velocityY = 0f

        // assert that progress is increased when increasing touchX
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - INITIAL_X_LEFT_EDGE) / MAX_DISTANCE)

        // assert that progress is reset to 0 when start location is updated
        linearTracker.updateStartLocation()
        linearTracker.assertProgress(0f)

        // assert that progress remains 0 when touchX is decreased
        touchX -= 50
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // assert that progress uses new minimal touchX for progress calculation
        val newInitialTouchX = touchX
        touchX += 100
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((touchX - newInitialTouchX) / MAX_DISTANCE)

        // assert the same for triggerBack==true
        linearTracker.triggerBack = true
        linearTracker.assertProgress((touchX - newInitialTouchX) / MAX_DISTANCE)
    }

    @Test
    fun restartingGesture_resetsInitialTouchX_rightEdge() {
        val linearTracker = linearTouchTracker()
        linearTracker.setGestureStartLocation(INITIAL_X_RIGHT_EDGE, 0f, BackEvent.EDGE_RIGHT)

        var touchX = INITIAL_X_RIGHT_EDGE - 100f
        val velocityX = 0f
        val velocityY = 0f

        // assert that progress is increased when decreasing touchX
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((INITIAL_X_RIGHT_EDGE - touchX) / MAX_DISTANCE)

        // assert that progress is reset to 0 when start location is updated
        linearTracker.updateStartLocation()
        linearTracker.assertProgress(0f)

        // assert that progress remains 0 when touchX is increased
        touchX += 50
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress(0f)

        // assert that progress uses new maximal touchX for progress calculation
        val newInitialTouchX = touchX
        touchX -= 100
        linearTracker.update(touchX, 0f, velocityX, velocityY)
        linearTracker.assertProgress((newInitialTouchX - touchX) / MAX_DISTANCE)

        // assert the same for triggerBack==true
        linearTracker.triggerBack = true
        linearTracker.assertProgress((newInitialTouchX - touchX) / MAX_DISTANCE)
    }

    companion object {
        private const val MAX_DISTANCE = 500f
        private const val LINEAR_DISTANCE = 400f
        private const val NON_LINEAR_DISTANCE = MAX_DISTANCE - LINEAR_DISTANCE
        private const val NON_LINEAR_FACTOR = 0.2f
        private const val INITIAL_X_LEFT_EDGE = 5f
        private const val INITIAL_X_RIGHT_EDGE = MAX_DISTANCE - INITIAL_X_LEFT_EDGE
    }
}