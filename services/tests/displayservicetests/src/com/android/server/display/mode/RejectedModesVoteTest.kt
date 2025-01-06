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
class RejectedModesVoteTest {
    private val rejectedModes = setOf(1, 2)

    private val otherMode = 2

    private lateinit var rejectedModesVote: RejectedModesVote

    @Before
    fun setUp() {
        rejectedModesVote = RejectedModesVote(rejectedModes)
    }

    @Test
    fun addsRejectedModeIds_summaryIsEmpty() {
        val summary = createVotesSummary()

        rejectedModesVote.updateSummary(summary)

        assertThat(summary.rejectedModeIds).containsExactlyElementsIn(rejectedModes)
    }

    @Test
    fun addsRejectedModeIds_summaryIsNotEmpty() {
        val summary = createVotesSummary()
        summary.rejectedModeIds.add(otherMode)

        rejectedModesVote.updateSummary(summary)

        assertThat(summary.rejectedModeIds).containsExactlyElementsIn(rejectedModes + otherMode)
    }
}