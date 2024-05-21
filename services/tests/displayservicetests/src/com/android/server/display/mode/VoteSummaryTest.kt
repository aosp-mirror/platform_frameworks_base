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

import android.view.Display
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(TestParameterInjector::class)
class VoteSummaryTest {

    enum class SupportedRefreshRatesTestCase(
            val supportedModesVoteEnabled: Boolean,
            internal val summaryRefreshRates: List<SupportedRefreshRatesVote.RefreshRates>?,
            val modesToFilter: Array<Display.Mode>,
            val expectedModeIds: List<Int>
    ) {
        HAS_NO_MATCHING_VOTE(true,
                listOf(SupportedRefreshRatesVote.RefreshRates(60f, 60f)),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf()
        ),
        HAS_SINGLE_MATCHING_VOTE(true,
                listOf(SupportedRefreshRatesVote.RefreshRates(60f, 90f)),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(3)
        ),
        HAS_MULTIPLE_MATCHING_VOTES(true,
                listOf(SupportedRefreshRatesVote.RefreshRates(60f, 90f),
                        SupportedRefreshRatesVote.RefreshRates(90f, 90f)),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 3)
        ),
        HAS_NO_SUPPORTED_MODES(true,
                listOf(),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf()
        ),
        HAS_NULL_SUPPORTED_MODES(true,
                null,
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 2, 3)
        ),
        HAS_SUPPORTED_MODES_VOTE_DISABLED(false,
                listOf(),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 2, 3)
        ),
    }

    @Test
    fun `filters modes for summary supportedRefreshRates`(
            @TestParameter testCase: SupportedRefreshRatesTestCase
    ) {
        val summary = createSummary(testCase.supportedModesVoteEnabled)
        summary.supportedRefreshRates = testCase.summaryRefreshRates

        val result = summary.filterModes(testCase.modesToFilter)

        assertThat(result.map { it.modeId }).containsExactlyElementsIn(testCase.expectedModeIds)
    }

    enum class SupportedModesTestCase(
            val supportedModesVoteEnabled: Boolean,
            internal val summarySupportedModes: List<Int>?,
            val modesToFilter: Array<Display.Mode>,
            val expectedModeIds: List<Int>
    ) {
        HAS_NO_MATCHING_VOTE(true,
                listOf(4, 5),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf()
        ),
        HAS_SINGLE_MATCHING_VOTE(true,
                listOf(3),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(3)
        ),
        HAS_MULTIPLE_MATCHING_VOTES(true,
                listOf(1, 3),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 3)
        ),
        HAS_NO_SUPPORTED_MODES(true,
                listOf(),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf()
        ),
        HAS_NULL_SUPPORTED_MODES(true,
                null,
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 2, 3)
        ),
        HAS_SUPPORTED_MODES_VOTE_DISABLED(false,
                listOf(),
                arrayOf(createMode(1, 90f, 90f),
                        createMode(2, 90f, 60f),
                        createMode(3, 60f, 90f)),
                listOf(1, 2, 3)
        ),
    }

    @Test
    fun `filters modes for summary supportedModes`(
            @TestParameter testCase: SupportedModesTestCase
    ) {
        val summary = createSummary(testCase.supportedModesVoteEnabled)
        summary.supportedModeIds = testCase.summarySupportedModes

        val result = summary.filterModes(testCase.modesToFilter)

        assertThat(result.map { it.modeId }).containsExactlyElementsIn(testCase.expectedModeIds)
    }
}
private fun createMode(modeId: Int, refreshRate: Float, vsyncRate: Float): Display.Mode {
    return Display.Mode(modeId, 600, 800, refreshRate, vsyncRate, false,
            FloatArray(0), IntArray(0))
}

private fun createSummary(supportedModesVoteEnabled: Boolean): VoteSummary {
    val summary = createVotesSummary(supportedModesVoteEnabled = supportedModesVoteEnabled)
    summary.width = 600
    summary.height = 800
    summary.maxPhysicalRefreshRate = Float.POSITIVE_INFINITY
    summary.maxRenderFrameRate = Float.POSITIVE_INFINITY

    return summary
}
