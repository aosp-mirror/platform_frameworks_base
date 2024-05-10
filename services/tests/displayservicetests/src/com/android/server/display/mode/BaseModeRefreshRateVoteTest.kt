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

package com.android.server.display.mode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


private const val BASE_REFRESH_RATE = 60f
private const val OTHER_BASE_REFRESH_RATE = 90f

@SmallTest
@RunWith(AndroidJUnit4::class)
class BaseModeRefreshRateVoteTest {

    private lateinit var baseModeVote: BaseModeRefreshRateVote

    @Before
    fun setUp() {
        baseModeVote = BaseModeRefreshRateVote(BASE_REFRESH_RATE)
    }

    @Test
    fun `updates summary with base mode refresh rate if not set`() {
        val summary = createVotesSummary()

        baseModeVote.updateSummary(summary)

        assertThat(summary.appRequestBaseModeRefreshRate).isEqualTo(BASE_REFRESH_RATE)
    }

    @Test
    fun `keeps summary base mode refresh rate if set`() {
        val summary = createVotesSummary()
        summary.appRequestBaseModeRefreshRate = OTHER_BASE_REFRESH_RATE

        baseModeVote.updateSummary(summary)

        assertThat(summary.appRequestBaseModeRefreshRate).isEqualTo(OTHER_BASE_REFRESH_RATE)
    }

    @Test
    fun `keeps summary with base mode refresh rate if vote refresh rate is negative`() {
        val invalidBaseModeVote = BaseModeRefreshRateVote(-10f)
        val summary = createVotesSummary()

        invalidBaseModeVote.updateSummary(summary)

        assertThat(summary.appRequestBaseModeRefreshRate).isZero()
    }
}