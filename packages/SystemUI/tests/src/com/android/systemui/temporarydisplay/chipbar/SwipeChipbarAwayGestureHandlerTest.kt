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

package com.android.systemui.temporarydisplay.chipbar

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class SwipeChipbarAwayGestureHandlerTest : SysuiTestCase() {

    private lateinit var underTest: SwipeChipbarAwayGestureHandler

    @Before
    fun setUp() {
        underTest = SwipeChipbarAwayGestureHandler(context, FakeDisplayTracker(mContext), mock())
    }

    @Test
    fun startOfGestureIsWithinBounds_noViewFetcher_returnsFalse() {
        assertThat(underTest.startOfGestureIsWithinBounds(createMotionEvent())).isFalse()
    }

    @Test
    fun startOfGestureIsWithinBounds_usesViewFetcher_aboveBottom_returnsTrue() {
        val view = createMockView()

        underTest.setViewFetcher { view }

        val motionEvent = createMotionEvent(y = VIEW_BOTTOM - 100f)
        assertThat(underTest.startOfGestureIsWithinBounds(motionEvent)).isTrue()
    }

    @Test
    fun startOfGestureIsWithinBounds_usesViewFetcher_slightlyBelowBottom_returnsTrue() {
        val view = createMockView()

        underTest.setViewFetcher { view }

        val motionEvent = createMotionEvent(y = VIEW_BOTTOM + 20f)
        assertThat(underTest.startOfGestureIsWithinBounds(motionEvent)).isTrue()
    }

    @Test
    fun startOfGestureIsWithinBounds_usesViewFetcher_tooFarDown_returnsFalse() {
        val view = createMockView()

        underTest.setViewFetcher { view }

        val motionEvent = createMotionEvent(y = VIEW_BOTTOM * 4f)
        assertThat(underTest.startOfGestureIsWithinBounds(motionEvent)).isFalse()
    }

    @Test
    fun startOfGestureIsWithinBounds_viewFetcherReset_returnsFalse() {
        val view = createMockView()

        underTest.setViewFetcher { view }

        val motionEvent = createMotionEvent(y = VIEW_BOTTOM - 100f)
        assertThat(underTest.startOfGestureIsWithinBounds(motionEvent)).isTrue()

        underTest.resetViewFetcher()
        assertThat(underTest.startOfGestureIsWithinBounds(motionEvent)).isFalse()
    }

    private fun createMotionEvent(y: Float = 0f): MotionEvent {
        return MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, y, 0)
    }

    private fun createMockView(): View {
        return mock<View>().also {
            doAnswer { invocation ->
                    val out: Rect = invocation.getArgument(0)
                    out.set(0, 0, 0, VIEW_BOTTOM)
                    null
                }
                .whenever(it)
                .getBoundsOnScreen(any())
        }
    }

    private companion object {
        const val VIEW_BOTTOM = 455
    }
}
