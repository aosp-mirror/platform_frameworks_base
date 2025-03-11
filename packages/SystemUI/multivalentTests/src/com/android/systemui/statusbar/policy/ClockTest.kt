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

package com.android.systemui.statusbar.policy

import android.testing.TestableLooper
import android.view.View.MeasureSpec.UNSPECIFIED
import android.view.View.MeasureSpec.makeMeasureSpec
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.runner.RunWith

import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ClockTest : SysuiTestCase() {
    private lateinit var clockView: Clock

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            val container = LinearLayout(context)
            val lp = LinearLayout.LayoutParams(1000, WRAP_CONTENT)
            container.layoutParams = lp
            clockView = Clock(context, null)
            container.addView(clockView)
            measureClock()
        }
    }

    @Test
    fun testWidthDoesNotDecrease_sameCharLength() {
        // GIVEN time is narrow
        clockView.text = ONE_3
        measureClock()
        val width1 = clockView.measuredWidth

        // WHEN the text changes to be wider characters
        clockView.text = ZERO_3
        measureClock()
        val width2 = clockView.measuredWidth

        // THEN the width should be wider (or equals when using monospace font)
        assertThat(width2).isAtLeast(width1)
    }

    @Test
    fun testWidthDoesNotDecrease_narrowerFont_sameNumberOfChars() {
        // GIVEN time is wide
        clockView.text = ZERO_3
        measureClock()
        val width1 = clockView.measuredWidth

        // WHEN the text changes to a narrower font
        clockView.text = ONE_3
        measureClock()
        val width2 = clockView.measuredWidth

        // THEN the width should not have decreased, and they should in fact be the same
        assertThat(width2).isEqualTo(width1)
    }

    @Test
    fun testWidthIncreases_whenCharsChanges() {
        // GIVEN wide 3-char text
        clockView.text = ZERO_3
        measureClock()
        val width1 = clockView.measuredWidth

        // WHEN text changes to 4-char wide text
        clockView.text = ZERO_4
        measureClock()
        val width2 = clockView.measuredWidth

        // THEN the text field is wider
        assertThat(width2).isGreaterThan(width1)
    }

    @Test
    fun testWidthDecreases_whenCharsChange_longToShort() {
        // GIVEN wide 4-char text
        clockView.text = ZERO_4
        measureClock()
        val width1 = clockView.measuredWidth

        // WHEN number of characters changes to a narrow 3-char text
        clockView.text = ONE_3
        measureClock()
        val width2 = clockView.measuredWidth

        // THEN the width can shrink, because number of chars changed
        assertThat(width2).isLessThan(width1)
    }

    private fun measureClock() {
        clockView.measure(
                makeMeasureSpec(0, UNSPECIFIED),
                makeMeasureSpec(0, UNSPECIFIED)
        )
    }
}

/**
 * In a non-monospace font, it is expected that "0:00" is wider than "1:11"
 */
private const val ZERO_3 = "0:00"
private const val ZERO_4 = "00:00"
private const val ONE_3 = "1:11"
private const val ONE_4 = "11:11"
