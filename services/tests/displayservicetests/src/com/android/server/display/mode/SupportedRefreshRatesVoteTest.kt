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


@SmallTest
@RunWith(AndroidJUnit4::class)
class SupportedRefreshRatesVoteTest {
    private val refreshRates = listOf(
            SupportedRefreshRatesVote.RefreshRates(60f, 90f),
            SupportedRefreshRatesVote.RefreshRates(120f, 240f)
    )

    private val otherMode = SupportedRefreshRatesVote.RefreshRates(120f, 120f)

    private lateinit var supportedRefreshRatesVote: SupportedRefreshRatesVote

    @Before
    fun setUp() {
        supportedRefreshRatesVote = SupportedRefreshRatesVote(refreshRates)
    }

    @Test
    fun `adds supported refresh rates if supportedModes in summary is null`() {
        val summary = createVotesSummary()

        supportedRefreshRatesVote.updateSummary(summary)

        assertThat(summary.supportedRefreshRates).containsExactlyElementsIn(refreshRates)
    }

    @Test
    fun `does not add supported refresh rates if summary has empty list of refresh rates`() {
        val summary = createVotesSummary()
        summary.supportedRefreshRates = ArrayList()

        supportedRefreshRatesVote.updateSummary(summary)

        assertThat(summary.supportedRefreshRates).isEmpty()
    }

    @Test
    fun `filters out supported refresh rates that does not match vote`() {
        val summary = createVotesSummary()
        summary.supportedRefreshRates = ArrayList(listOf(otherMode, refreshRates[0]))

        supportedRefreshRatesVote.updateSummary(summary)

        assertThat(summary.supportedRefreshRates).containsExactly(refreshRates[0])
    }
}