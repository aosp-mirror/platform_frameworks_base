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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit


import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class CombinedVoteTest {
    private lateinit var combinedVote: CombinedVote

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    private val mockVote1 = mock<Vote>()
    private val mockVote2 = mock<Vote>()

    @Before
    fun setUp() {
        combinedVote = CombinedVote(listOf(mockVote1, mockVote2))
    }

    @Test
    fun `delegates update to children`() {
        val summary = createVotesSummary()

        combinedVote.updateSummary(summary)

        verify(mockVote1).updateSummary(summary)
        verify(mockVote2).updateSummary(summary)
    }
}