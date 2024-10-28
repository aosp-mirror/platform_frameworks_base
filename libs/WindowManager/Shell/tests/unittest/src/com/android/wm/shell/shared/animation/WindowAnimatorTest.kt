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

package com.android.wm.shell.shared.animation

import android.animation.ValueAnimator
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.SurfaceControl
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread
import com.android.app.animation.Interpolators
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class WindowAnimatorTest {

    private val transaction = mock<SurfaceControl.Transaction>()
    private val change = mock<TransitionInfo.Change>()
    private val leash = mock<SurfaceControl>()

    private val displayMetrics = DisplayMetrics().apply { density = 1f }

    @Before
    fun setup() {
        whenever(change.leash).thenReturn(leash)
        whenever(change.startAbsBounds).thenReturn(START_BOUNDS)
        whenever(transaction.setPosition(any(), anyFloat(), anyFloat())).thenReturn(transaction)
        whenever(transaction.setScale(any(), anyFloat(), anyFloat())).thenReturn(transaction)
    }

    @Test
    fun createBoundsAnimator_returnsCorrectDefaultAnimatorParams() = runOnUiThread {
        val boundsAnimParams =
            WindowAnimator.BoundsAnimationParams(
                durationMs = 100L,
                interpolator = Interpolators.STANDARD_ACCELERATE,
            )

        val valueAnimator =
            WindowAnimator.createBoundsAnimator(
                displayMetrics,
                boundsAnimParams,
                change,
                transaction
            )

        assertThat(valueAnimator.duration).isEqualTo(100L)
        assertThat(valueAnimator.interpolator).isEqualTo(Interpolators.STANDARD_ACCELERATE)
        assertStartAndEndBounds(valueAnimator, startBounds = START_BOUNDS, endBounds = START_BOUNDS)
    }

    @Test
    fun createBoundsAnimator_startScaleAndOffset_returnsCorrectBounds() = runOnUiThread {
        val bounds = Rect(/* left= */ 100, /* top= */ 200, /* right= */ 300, /* bottom= */ 400)
        whenever(change.startAbsBounds).thenReturn(bounds)
        val boundsAnimParams =
            WindowAnimator.BoundsAnimationParams(
                durationMs = 100L,
                startOffsetYDp = 10f,
                startScale = 0.5f,
                interpolator = Interpolators.STANDARD_ACCELERATE,
            )

        val valueAnimator =
            WindowAnimator.createBoundsAnimator(
                displayMetrics,
                boundsAnimParams,
                change,
                transaction
            )

        assertStartAndEndBounds(
            valueAnimator,
            startBounds =
                Rect(/* left= */ 150, /* top= */ 260, /* right= */ 250, /* bottom= */ 360),
            endBounds = bounds,
        )
    }

    @Test
    fun createBoundsAnimator_endScaleAndOffset_returnsCorrectBounds() = runOnUiThread {
        val bounds = Rect(/* left= */ 100, /* top= */ 200, /* right= */ 300, /* bottom= */ 400)
        whenever(change.startAbsBounds).thenReturn(bounds)
        val boundsAnimParams =
            WindowAnimator.BoundsAnimationParams(
                durationMs = 100L,
                endOffsetYDp = 10f,
                endScale = 0.5f,
                interpolator = Interpolators.STANDARD_ACCELERATE,
            )

        val valueAnimator =
            WindowAnimator.createBoundsAnimator(
                displayMetrics,
                boundsAnimParams,
                change,
                transaction
            )

        assertStartAndEndBounds(
            valueAnimator,
            startBounds = bounds,
            endBounds = Rect(/* left= */ 150, /* top= */ 260, /* right= */ 250, /* bottom= */ 360),
        )
    }

    private fun assertStartAndEndBounds(
        valueAnimator: ValueAnimator,
        startBounds: Rect,
        endBounds: Rect,
    ) {
        valueAnimator.start()
        valueAnimator.animatedValue
        assertThat(valueAnimator.animatedValue).isEqualTo(startBounds)
        valueAnimator.end()
        assertThat(valueAnimator.animatedValue).isEqualTo(endBounds)
    }

    companion object {
        private val START_BOUNDS =
            Rect(/* left= */ 10, /* top= */ 20, /* right= */ 30, /* bottom= */ 40)
    }
}
