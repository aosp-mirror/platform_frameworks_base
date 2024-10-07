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

import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val MIN_REFRESH_RATE = 60f
private const val MAX_REFRESH_RATE = 90f

@SmallTest
@RunWith(TestParameterInjector::class)
class RenderVoteTest {

    private lateinit var renderVote: RefreshRateVote.RenderVote

    @Before
    fun setUp() {
        renderVote = RefreshRateVote.RenderVote(MIN_REFRESH_RATE, MAX_REFRESH_RATE)
    }

    @Test
    fun updatesMinRenderFrameRateWithBiggerValue() {
        val summary = createVotesSummary()
        summary.minRenderFrameRate = 45f

        renderVote.updateSummary(summary)

        assertThat(summary.minRenderFrameRate).isEqualTo(MIN_REFRESH_RATE)
    }

    @Test
    fun doesNotUpdateMinRenderFrameRateWithSmallerValue() {
        val summary = createVotesSummary()
        summary.minRenderFrameRate = 75f

        renderVote.updateSummary(summary)

        assertThat(summary.minRenderFrameRate).isEqualTo(75f)
    }

    @Test
    fun updatesMaxPRenderFrameRateWithSmallerValue() {
        val summary = createVotesSummary()
        summary.maxRenderFrameRate = 120f

        renderVote.updateSummary(summary)

        assertThat(summary.maxRenderFrameRate).isEqualTo(MAX_REFRESH_RATE)
    }

    @Test
    fun doesNotUpdateMaxPRenderFrameRateWithBiggerValue() {
        val summary = createVotesSummary()
        summary.maxRenderFrameRate = 75f

        renderVote.updateSummary(summary)

        assertThat(summary.maxRenderFrameRate).isEqualTo(75f)
    }

    @Test
    fun updatesMinPhysicalRefreshRateWithBiggerValue() {
        val summary = createVotesSummary()
        summary.minPhysicalRefreshRate = 45f

        renderVote.updateSummary(summary)

        assertThat(summary.minPhysicalRefreshRate).isEqualTo(MIN_REFRESH_RATE)
    }

    @Test
    fun doesNotUpdateMinPhysicalRefreshRateWithSmallerValue() {
        val summary = createVotesSummary()
        summary.minPhysicalRefreshRate = 75f

        renderVote.updateSummary(summary)

        assertThat(summary.minPhysicalRefreshRate).isEqualTo(75f)
    }
}