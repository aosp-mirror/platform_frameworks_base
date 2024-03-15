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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@SmallTest
@RunWith(AndroidJUnit4::class)
class SupportedModesVoteTest {
    private val supportedModes = listOf(1, 2, 4)

    private val otherMode = 5

    private lateinit var supportedModesVote: SupportedModesVote

    @Before
    fun setUp() {
        supportedModesVote = SupportedModesVote(supportedModes)
    }

    @Test
    fun `adds supported mode ids if supportedModeIds in summary is null`() {
        val summary = createVotesSummary()

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModeIds).containsExactlyElementsIn(supportedModes)
    }

    @Test
    fun `does not add supported mode ids if summary has empty list of modeIds`() {
        val summary = createVotesSummary()
        summary.supportedModeIds = ArrayList()

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModeIds).isEmpty()
    }

    @Test
    fun `filters out modes that does not match vote`() {
        val summary = createVotesSummary()
        summary.supportedModeIds = ArrayList(listOf(otherMode, supportedModes[0]))

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModeIds).containsExactly(supportedModes[0])
    }
}