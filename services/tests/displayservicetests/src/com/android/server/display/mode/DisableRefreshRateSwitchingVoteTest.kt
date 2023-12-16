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
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(TestParameterInjector::class)
class DisableRefreshRateSwitchingVoteTest {

    @Test
    fun `disabled refresh rate switching is not changed`(
            @TestParameter voteDisableSwitching: Boolean
    ) {
        val summary = createVotesSummary()
        summary.disableRefreshRateSwitching = true
        val vote = DisableRefreshRateSwitchingVote(voteDisableSwitching)

        vote.updateSummary(summary)

        assertThat(summary.disableRefreshRateSwitching).isTrue()
    }

    @Test
    fun `disables refresh rate switching if requested`() {
        val summary = createVotesSummary()
        val vote = DisableRefreshRateSwitchingVote(true)

        vote.updateSummary(summary)

        assertThat(summary.disableRefreshRateSwitching).isTrue()
    }

    @Test
    fun `does not disable refresh rate switching if not requested`() {
        val summary = createVotesSummary()
        val vote = DisableRefreshRateSwitchingVote(false)

        vote.updateSummary(summary)

        assertThat(summary.disableRefreshRateSwitching).isFalse()
    }
}