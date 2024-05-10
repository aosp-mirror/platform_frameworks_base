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


private const val WIDTH = 800
private const val HEIGHT = 1600
private const val MIN_WIDTH = 400
private const val MIN_HEIGHT = 1200
@SmallTest
@RunWith(AndroidJUnit4::class)
class SizeVoteTest {
    private lateinit var sizeVote: SizeVote

    @Before
    fun setUp() {
        sizeVote = SizeVote(WIDTH, HEIGHT, MIN_WIDTH, MIN_HEIGHT)
    }

    @Test
    fun `updates size if width and height not set and display resolution voting disabled`() {
        val summary = createVotesSummary(isDisplayResolutionRangeVotingEnabled = false)
        summary.width = Vote.INVALID_SIZE
        summary.height = Vote.INVALID_SIZE
        summary.minWidth = 100
        summary.minHeight = 200

        sizeVote.updateSummary(summary)

        assertThat(summary.width).isEqualTo(WIDTH)
        assertThat(summary.height).isEqualTo(HEIGHT)
        assertThat(summary.minWidth).isEqualTo(MIN_WIDTH)
        assertThat(summary.minHeight).isEqualTo(MIN_HEIGHT)
    }

    @Test
    fun `does not update size if width set and display resolution voting disabled`() {
        val summary = createVotesSummary(isDisplayResolutionRangeVotingEnabled = false)
        summary.width = 150
        summary.height = Vote.INVALID_SIZE
        summary.minWidth = 100
        summary.minHeight = 200

        sizeVote.updateSummary(summary)

        assertThat(summary.width).isEqualTo(150)
        assertThat(summary.height).isEqualTo(Vote.INVALID_SIZE)
        assertThat(summary.minWidth).isEqualTo(100)
        assertThat(summary.minHeight).isEqualTo(200)
    }

    @Test
    fun `does not update size if height set and display resolution voting disabled`() {
        val summary = createVotesSummary(isDisplayResolutionRangeVotingEnabled = false)
        summary.width = Vote.INVALID_SIZE
        summary.height = 250
        summary.minWidth = 100
        summary.minHeight = 200

        sizeVote.updateSummary(summary)

        assertThat(summary.width).isEqualTo(Vote.INVALID_SIZE)
        assertThat(summary.height).isEqualTo(250)
        assertThat(summary.minWidth).isEqualTo(100)
        assertThat(summary.minHeight).isEqualTo(200)
    }

    @Test
    fun `updates width if summary has more and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 850

        sizeVote.updateSummary(summary)

        assertThat(summary.width).isEqualTo(WIDTH)
    }

    @Test
    fun `does not update width if summary has less and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 750

        sizeVote.updateSummary(summary)

        assertThat(summary.width).isEqualTo(750)
    }

    @Test
    fun `updates height if summary has more and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.height = 1650

        sizeVote.updateSummary(summary)

        assertThat(summary.height).isEqualTo(HEIGHT)
    }

    @Test
    fun `does not update height if summary has less and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.height = 1550

        sizeVote.updateSummary(summary)

        assertThat(summary.height).isEqualTo(1550)
    }

    @Test
    fun `updates minWidth if summary has less and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 150
        summary.minWidth = 350

        sizeVote.updateSummary(summary)

        assertThat(summary.minWidth).isEqualTo(MIN_WIDTH)
    }

    @Test
    fun `does not update minWidth if summary has more and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 150
        summary.minWidth = 450

        sizeVote.updateSummary(summary)

        assertThat(summary.minWidth).isEqualTo(450)
    }

    @Test
    fun `updates minHeight if summary has less and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 150
        summary.minHeight = 1150

        sizeVote.updateSummary(summary)

        assertThat(summary.minHeight).isEqualTo(MIN_HEIGHT)
    }

    @Test
    fun `does not update minHeight if summary has more and display resolution voting enabled`() {
        val summary = createVotesSummary()
        summary.width = 150
        summary.minHeight = 1250

        sizeVote.updateSummary(summary)

        assertThat(summary.minHeight).isEqualTo(1250)
    }
}