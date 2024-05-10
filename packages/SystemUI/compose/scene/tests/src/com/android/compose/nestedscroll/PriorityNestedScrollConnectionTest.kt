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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.compose.nestedscroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PriorityNestedScrollConnectionTest {
    private var canStartPreScroll = false
    private var canStartPostScroll = false
    private var canStartPostFling = false
    private var canContinueScroll = false
    private var isStarted = false
    private var lastScroll: Offset? = null
    private var returnOnScroll = Offset.Zero
    private var lastStop: Velocity? = null
    private var returnOnStop = Velocity.Zero

    private val scrollConnection =
        PriorityNestedScrollConnection(
            canStartPreScroll = { _, _ -> canStartPreScroll },
            canStartPostScroll = { _, _ -> canStartPostScroll },
            canStartPostFling = { canStartPostFling },
            canContinueScroll = { canContinueScroll },
            canScrollOnFling = false,
            onStart = { isStarted = true },
            onScroll = {
                lastScroll = it
                returnOnScroll
            },
            onStop = {
                lastStop = it
                returnOnStop
            },
        )

    private val offset1 = Offset(1f, 1f)
    private val offset2 = Offset(2f, 2f)
    private val velocity1 = Velocity(1f, 1f)
    private val velocity2 = Velocity(2f, 2f)

    @Test
    fun step1_priorityModeShouldStartOnlyOnPreScroll() = runTest {
        canStartPreScroll = true

        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreScroll(available = Offset.Zero, source = NestedScrollSource.Drag)
        assertThat(isStarted).isEqualTo(true)
    }

    private fun startPriorityModePostScroll() {
        canStartPostScroll = true
        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
    }

    @Test
    fun step1_priorityModeShouldStartOnlyOnPostScroll() = runTest {
        canStartPostScroll = true

        scrollConnection.onPreScroll(available = Offset.Zero, source = NestedScrollSource.Drag)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        startPriorityModePostScroll()
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun step1_priorityModeShouldStartOnlyIfAllowed() {
        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(isStarted).isEqualTo(false)

        startPriorityModePostScroll()
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun step1_onPriorityModeStarted_receiveAvailableOffset() {
        canStartPostScroll = true

        scrollConnection.onPostScroll(
            consumed = offset1,
            available = offset2,
            source = NestedScrollSource.Drag
        )

        assertThat(lastScroll).isEqualTo(offset2)
    }

    @Test
    fun step2_onPriorityMode_shouldContinueIfAllowed() {
        startPriorityModePostScroll()
        canContinueScroll = true

        scrollConnection.onPreScroll(available = offset1, source = NestedScrollSource.Drag)
        assertThat(lastScroll).isEqualTo(offset1)

        canContinueScroll = false
        scrollConnection.onPreScroll(available = offset2, source = NestedScrollSource.Drag)
        assertThat(lastScroll).isNotEqualTo(offset2)
        assertThat(lastScroll).isEqualTo(offset1)
    }

    @Test
    fun step3a_onPriorityMode_shouldStopIfCannotContinue() {
        startPriorityModePostScroll()
        canContinueScroll = false

        scrollConnection.onPreScroll(available = Offset.Zero, source = NestedScrollSource.Drag)

        assertThat(lastStop).isNotNull()
    }

    @Test
    fun step3b_onPriorityMode_shouldStopOnFling() = runTest {
        startPriorityModePostScroll()
        canContinueScroll = true

        scrollConnection.onPreFling(available = Velocity.Zero)

        assertThat(lastStop).isNotNull()
    }

    @Test
    fun step3c_onPriorityMode_shouldStopOnReset() {
        startPriorityModePostScroll()
        canContinueScroll = true

        scrollConnection.reset()

        assertThat(lastStop).isNotNull()
    }

    @Test
    fun receive_onPostFling() = runTest {
        canStartPostFling = true

        scrollConnection.onPostFling(
            consumed = velocity1,
            available = velocity2,
        )

        assertThat(lastStop).isEqualTo(velocity2)
    }

    @Test
    fun step1_priorityModeShouldStartOnlyOnPostFling() = runTest {
        canStartPostFling = true

        scrollConnection.onPreScroll(available = Offset.Zero, source = NestedScrollSource.Drag)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = NestedScrollSource.Drag
        )
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(true)
    }
}
