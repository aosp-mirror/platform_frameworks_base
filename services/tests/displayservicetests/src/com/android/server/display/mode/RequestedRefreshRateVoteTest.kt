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

package com.android.server.display.mode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith


@SmallTest
@RunWith(AndroidJUnit4::class)
class RequestedRefreshRateVoteTest {

    @Test
    fun testUpdatesRequestedRefreshRates() {
        val refreshRate = 90f
        val vote = RequestedRefreshRateVote(refreshRate)
        val summary = createVotesSummary()

        vote.updateSummary(summary)

        assertThat(summary.requestedRefreshRates).hasSize(1)
        assertThat(summary.requestedRefreshRates).contains(refreshRate)
    }

    @Test
    fun testUpdatesRequestedRefreshRates_multipleVotes() {
        val refreshRate1 = 90f
        val vote1 = RequestedRefreshRateVote(refreshRate1)

        val refreshRate2 = 60f
        val vote2 = RequestedRefreshRateVote(refreshRate2)

        val summary = createVotesSummary()

        vote1.updateSummary(summary)
        vote2.updateSummary(summary)

        assertThat(summary.requestedRefreshRates).hasSize(2)
        assertThat(summary.requestedRefreshRates).containsExactly(refreshRate1, refreshRate2)
    }
}