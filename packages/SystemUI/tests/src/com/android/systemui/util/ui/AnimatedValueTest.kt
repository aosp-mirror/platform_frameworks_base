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

package com.android.systemui.util.ui

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AnimatedValueTest : SysuiTestCase() {

    @Test
    fun animatableEvent_updatesValue() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow(completionEvents = emptyFlow())
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = false))

        assertThat(value).isEqualTo(AnimatedValue(value = 1, isAnimating = false))
    }

    @Test
    fun animatableEvent_startAnimation() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow(completionEvents = emptyFlow())
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))

        assertThat(value).isEqualTo(AnimatedValue(value = 1, isAnimating = true))
    }

    @Test
    fun animatableEvent_startAnimation_alreadyAnimating() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow(completionEvents = emptyFlow())
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))
        events.emit(AnimatableEvent(value = 2, startAnimating = true))

        assertThat(value).isEqualTo(AnimatedValue(value = 2, isAnimating = true))
    }

    @Test
    fun animatedValue_stopAnimating() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val stopEvent = MutableSharedFlow<Unit>()
        val values = events.toAnimatedValueFlow(completionEvents = stopEvent)
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))
        stopEvent.emit(Unit)

        assertThat(value).isEqualTo(AnimatedValue(value = 1, isAnimating = false))
    }

    @Test
    fun animatedValue_stopAnimating_notAnimating() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val stopEvent = MutableSharedFlow<Unit>()
        val values = events.toAnimatedValueFlow(completionEvents = stopEvent)
        values.launchIn(backgroundScope)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = false))

        assertThat(stopEvent.subscriptionCount.value).isEqualTo(0)
    }
}
