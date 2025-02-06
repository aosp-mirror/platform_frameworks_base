/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.text.format.DateUtils.formatElapsedTime
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ChronometerStateTest : SysuiTestCase() {

    private lateinit var mockTimeSource: MutableTimeSource

    @Before
    fun setup() {
        mockTimeSource = MutableTimeSource()
    }

    @Test
    fun initialText_isCorrect() = runTest {
        val state = ChronometerState(mockTimeSource, 0L)
        assertThat(state.currentTimeText).isEqualTo(formatElapsedTime(0))
    }

    @Test
    fun textUpdates_withTime() = runTest {
        val startTime = 1000L
        val state = ChronometerState(mockTimeSource, startTime)
        val job = launch { state.run() }

        val elapsedTime = 5000L
        mockTimeSource.time = startTime + elapsedTime
        advanceTimeBy(elapsedTime)
        assertThat(state.currentTimeText).isEqualTo(formatElapsedTime(elapsedTime / 1000))

        job.cancelAndJoin()
    }

    @Test
    fun textUpdates_toLargerValue() = runTest {
        val startTime = 1000L
        val state = ChronometerState(mockTimeSource, startTime)
        val job = launch { state.run() }

        val elapsedTime = 15000L
        mockTimeSource.time = startTime + elapsedTime
        advanceTimeBy(elapsedTime)
        assertThat(state.currentTimeText).isEqualTo(formatElapsedTime(elapsedTime / 1000))

        job.cancelAndJoin()
    }

    @Test
    fun textUpdates_afterResettingBase() = runTest {
        val initialElapsedTime = 30000L
        val startTime = 50000L
        val state = ChronometerState(mockTimeSource, startTime)
        val job = launch { state.run() }

        mockTimeSource.time = startTime + initialElapsedTime
        advanceTimeBy(initialElapsedTime)
        assertThat(state.currentTimeText).isEqualTo(formatElapsedTime(initialElapsedTime / 1000))

        job.cancelAndJoin()

        val newElapsedTime = 5000L
        val newStartTime = 100000L
        val newState = ChronometerState(mockTimeSource, newStartTime)
        val newJob = launch { newState.run() }

        mockTimeSource.time = newStartTime + newElapsedTime
        advanceTimeBy(newElapsedTime)
        assertThat(newState.currentTimeText).isEqualTo(formatElapsedTime(newElapsedTime / 1000))

        newJob.cancelAndJoin()
    }
}

/** A fake implementation of [TimeSource] that allows the caller to set the current time */
class MutableTimeSource(var time: Long = 0L) : TimeSource {
    override fun getCurrentTime(): Long {
        return time
    }
}
