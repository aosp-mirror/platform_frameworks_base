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
class SupportedModesVoteTest {
    private val supportedModes = listOf(
            SupportedModesVote.SupportedMode(60f, 90f ),
            SupportedModesVote.SupportedMode(120f, 240f )
    )

    private val otherMode = SupportedModesVote.SupportedMode(120f, 120f )

    private lateinit var supportedModesVote: SupportedModesVote

    @Before
    fun setUp() {
        supportedModesVote = SupportedModesVote(supportedModes)
    }

    @Test
    fun `adds supported modes if supportedModes in summary is null`() {
        val summary = createVotesSummary()

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModes).containsExactlyElementsIn(supportedModes)
    }

    @Test
    fun `does not add supported modes if summary has empty list of modes`() {
        val summary = createVotesSummary()
        summary.supportedModes = ArrayList()

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModes).isEmpty()
    }

    @Test
    fun `filters out modes that does not match vote`() {
        val summary = createVotesSummary()
        summary.supportedModes = ArrayList(listOf(otherMode, supportedModes[0]))

        supportedModesVote.updateSummary(summary)

        assertThat(summary.supportedModes).containsExactly(supportedModes[0])
    }
}