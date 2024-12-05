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

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollSource.Companion.UserInput
import androidx.compose.ui.unit.Velocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.test.runMonotonicClockTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PriorityNestedScrollConnectionTest {
    private var canStartPreScroll = false
    private var canStartPostScroll = false
    private var canStartPostFling = false
    private var canStopOnPreFling = true
    private var isStarted = false
    private var lastScroll: Float? = null
    private var consumeScroll = true
    private var lastStop: Float? = null
    private var isCancelled: Boolean = false
    private var onStopConsumeFlingToScroll = false
    private var onStopConsumeAll = true

    private val customFlingBehavior =
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                scrollBy(initialVelocity)
                // returns the remaining velocity: 1/3 remained + 2/3 consumed
                return initialVelocity / 3f
            }
        }

    private val scrollConnection =
        PriorityNestedScrollConnection(
            orientation = Orientation.Vertical,
            canStartPreScroll = { _, _, _ -> canStartPreScroll },
            canStartPostScroll = { _, _, _ -> canStartPostScroll },
            canStartPostFling = { canStartPostFling },
            onStart = { _ ->
                isStarted = true
                object : ScrollController {
                    override fun onScroll(deltaScroll: Float, source: NestedScrollSource): Float {
                        lastScroll = deltaScroll
                        return if (consumeScroll) deltaScroll else 0f
                    }

                    override suspend fun OnStopScope.onStop(initialVelocity: Float): Float {
                        lastStop = initialVelocity
                        var velocityConsumed = 0f
                        if (onStopConsumeFlingToScroll) {
                            velocityConsumed = flingToScroll(initialVelocity, customFlingBehavior)
                        }
                        if (onStopConsumeAll) {
                            velocityConsumed = initialVelocity
                        }
                        return velocityConsumed
                    }

                    override fun onCancel() {
                        isCancelled = true
                    }

                    override fun canStopOnPreFling() = canStopOnPreFling
                }
            },
        )

    @Test
    fun step1_priorityModeShouldStartOnlyOnPreScroll() = runTest {
        canStartPreScroll = true

        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = UserInput,
        )
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreScroll(available = Offset.Zero, source = UserInput)
        assertThat(isStarted).isEqualTo(true)
    }

    private fun startPriorityModePostScroll() {
        canStartPostScroll = true
        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(1f, 1f),
            source = UserInput,
        )
    }

    @Test
    fun step1_priorityModeShouldStartOnlyOnPostScroll() = runTest {
        canStartPostScroll = true

        scrollConnection.onPreScroll(available = Offset.Zero, source = UserInput)
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
            source = UserInput,
        )
        assertThat(isStarted).isEqualTo(false)

        startPriorityModePostScroll()
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun step1_onPriorityModeStarted_receiveAvailableOffset() {
        canStartPostScroll = true

        scrollConnection.onPostScroll(
            consumed = Offset(1f, 1f),
            available = Offset(2f, 2f),
            source = UserInput,
        )

        assertThat(lastScroll).isEqualTo(2f)
    }

    @Test
    fun step2_onPriorityMode_shouldContinueIfAllowed() {
        startPriorityModePostScroll()

        val scroll1 = scrollConnection.onPreScroll(available = Offset(0f, 1f), source = UserInput)
        assertThat(lastScroll).isEqualTo(1f)
        assertThat(scroll1.y).isEqualTo(1f)

        consumeScroll = false
        val scroll2 = scrollConnection.onPreScroll(available = Offset(0f, 2f), source = UserInput)
        assertThat(lastScroll).isEqualTo(2f)
        assertThat(scroll2.y).isEqualTo(0f)
    }

    @Test
    fun step3a_onPriorityMode_shouldCancelIfCannotContinue() {
        startPriorityModePostScroll()
        consumeScroll = false

        scrollConnection.onPreScroll(available = Offset(0f, 1f), source = UserInput)

        assertThat(isCancelled).isTrue()
    }

    @Test
    fun step3b_onPriorityMode_shouldStopOnFling() = runTest {
        startPriorityModePostScroll()

        scrollConnection.onPreFling(available = Velocity.Zero)

        assertThat(lastStop).isEqualTo(0f)
    }

    @Test
    fun onStopScrollUsingFlingToScroll() = runMonotonicClockTest {
        startPriorityModePostScroll()
        onStopConsumeFlingToScroll = true
        onStopConsumeAll = false
        lastScroll = Float.NaN

        val consumed = scrollConnection.onPreFling(available = Velocity(2f, 2f))

        val initialVelocity = 2f
        assertThat(lastStop).isEqualTo(initialVelocity)
        // flingToScroll should try to scroll the content, customFlingBehavior uses the velocity.
        assertThat(lastScroll).isEqualTo(2f)
        val remainingVelocity = initialVelocity / 3f
        // customFlingBehavior returns 2/3 of the vertical velocity.
        assertThat(consumed).isEqualTo(Velocity(0f, initialVelocity - remainingVelocity))
    }

    @Test
    fun ifCannotStopOnPreFling_shouldStopOnPostFling() = runTest {
        startPriorityModePostScroll()
        canStopOnPreFling = false

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(lastStop).isNull()

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(lastStop).isEqualTo(0f)
    }

    @Test
    fun step3c_onPriorityMode_shouldCancelOnReset() {
        startPriorityModePostScroll()

        scrollConnection.reset()

        assertThat(isCancelled).isTrue()
    }

    @Test
    fun receive_onPostFling() = runTest {
        canStartPostFling = true

        scrollConnection.onPostFling(consumed = Velocity(1f, 1f), available = Velocity(2f, 2f))

        assertThat(lastStop).isEqualTo(2f)
    }

    @Test
    fun step1_priorityModeShouldStartOnlyOnPostFling() = runTest {
        canStartPostFling = true

        scrollConnection.onPreScroll(available = Offset.Zero, source = UserInput)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset.Zero,
            source = UserInput,
        )
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPreFling(available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(false)

        scrollConnection.onPostFling(consumed = Velocity.Zero, available = Velocity.Zero)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun handleMultipleOnPreFlingCalls() = runTest {
        startPriorityModePostScroll()

        coroutineScope {
            launch { scrollConnection.onPreFling(available = Velocity.Zero) }
            launch { scrollConnection.onPreFling(available = Velocity.Zero) }
        }

        assertThat(lastStop).isEqualTo(0f)
    }
}
