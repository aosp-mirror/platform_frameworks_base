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

package com.android.systemui.statusbar.chips.ui.view

import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ChipBackgroundContainerTest : SysuiTestCase() {

    private lateinit var underTest: ChipBackgroundContainer

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            val chipView =
                LayoutInflater.from(context).inflate(R.layout.ongoing_activity_chip, null)
            underTest = chipView.requireViewById(R.id.ongoing_activity_chip_background)
        }
    }

    @Test
    fun onMeasure_maxHeightFetcherNotSet_usesDesired() {
        underTest.maxHeightFetcher = null

        underTest.measure(
            WIDTH_SPEC,
            View.MeasureSpec.makeMeasureSpec(123, View.MeasureSpec.EXACTLY),
        )

        assertThat(underTest.measuredHeight).isEqualTo(123)
    }

    @Test
    fun onMeasure_maxLargerThanDesired_usesDesired() {
        underTest.maxHeightFetcher = { 234 }

        underTest.measure(
            WIDTH_SPEC,
            View.MeasureSpec.makeMeasureSpec(123, View.MeasureSpec.EXACTLY),
        )

        assertThat(underTest.measuredHeight).isEqualTo(123)
    }

    @Test
    fun onMeasure_desiredLargerThanMax_usesMaxMinusOne() {
        underTest.maxHeightFetcher = { 234 }

        underTest.measure(
            WIDTH_SPEC,
            View.MeasureSpec.makeMeasureSpec(567, View.MeasureSpec.EXACTLY),
        )

        // We use the max - 1 to give a bit extra space
        assertThat(underTest.measuredHeight).isEqualTo(233)
    }

    private companion object {
        val WIDTH_SPEC = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    }
}
