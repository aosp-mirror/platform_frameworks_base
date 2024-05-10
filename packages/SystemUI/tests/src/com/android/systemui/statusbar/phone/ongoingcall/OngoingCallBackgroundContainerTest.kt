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

package com.android.systemui.statusbar.phone.ongoingcall

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class OngoingCallBackgroundContainerTest : SysuiTestCase() {

    private lateinit var underTest: OngoingCallBackgroundContainer

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        TestableLooper.get(this).runWithLooper {
            val chipView = LayoutInflater.from(context).inflate(R.layout.ongoing_call_chip, null)
            underTest = chipView.requireViewById(R.id.ongoing_call_chip_background)
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
