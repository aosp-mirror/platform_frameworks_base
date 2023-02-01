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
 * limitations under the License
 */

package com.android.systemui.shared.clocks

import android.testing.AndroidTestingRunner
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.TextAnimator
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class AnimatableClockViewTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var mockTextAnimator: TextAnimator
    private lateinit var clockView: AnimatableClockView

    @Before
    fun setUp() {
        val layoutInflater = LayoutInflater.from(context)
        clockView =
            layoutInflater.inflate(R.layout.clock_default_small, null) as AnimatableClockView
        clockView.textAnimatorFactory = { _, _ -> mockTextAnimator }
    }

    @Test
    fun validateColorAnimationRunsBeforeMeasure() {
        clockView.setColors(100, 200)
        clockView.animateAppearOnLockscreen()
        clockView.measure(50, 50)

        verify(mockTextAnimator).glyphFilter = null
        verify(mockTextAnimator).setTextStyle(300, -1.0f, 200, false, 350L, null, 0L, null)
        verifyNoMoreInteractions(mockTextAnimator)
    }

    @Test
    fun validateColorAnimationRunsAfterMeasure() {
        clockView.setColors(100, 200)
        clockView.measure(50, 50)
        clockView.animateAppearOnLockscreen()

        verify(mockTextAnimator, times(2)).glyphFilter = null
        verify(mockTextAnimator).setTextStyle(100, -1.0f, 200, false, 0L, null, 0L, null)
        verify(mockTextAnimator).setTextStyle(300, -1.0f, 200, true, 350L, null, 0L, null)
        verifyNoMoreInteractions(mockTextAnimator)
    }
}
