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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AnimatedValueTest : SysuiTestCase() {

    @Test
    fun animatableEvent_updatesValue() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow()
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = false))

        assertThat(value?.value).isEqualTo(1)
        assertThat(value?.isAnimating).isFalse()
    }

    @Test
    fun animatableEvent_startAnimation() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow()
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))

        assertThat(value?.value).isEqualTo(1)
        assertThat(value?.isAnimating).isTrue()
    }

    @Test
    fun animatableEvent_startAnimation_alreadyAnimating() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow()
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))
        events.emit(AnimatableEvent(value = 2, startAnimating = true))

        assertThat(value?.value).isEqualTo(2)
        assertThat(value?.isAnimating).isTrue()
    }

    @Test
    fun animatedValue_stopAnimating() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow()
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))
        assertThat(value?.isAnimating).isTrue()
        value?.stopAnimating()

        assertThat(value?.value).isEqualTo(1)
        assertThat(value?.isAnimating).isFalse()
    }

    @Test
    fun animatedValue_stopAnimatingPrevValue_doesNothing() = runTest {
        val events = MutableSharedFlow<AnimatableEvent<Int>>()
        val values = events.toAnimatedValueFlow()
        val value by collectLastValue(values)
        runCurrent()

        events.emit(AnimatableEvent(value = 1, startAnimating = true))
        val prevValue = value
        assertThat(prevValue?.isAnimating).isTrue()

        events.emit(AnimatableEvent(value = 2, startAnimating = true))
        assertThat(value?.isAnimating).isTrue()
        prevValue?.stopAnimating()

        assertThat(value?.value).isEqualTo(2)
        assertThat(value?.isAnimating).isTrue()
    }

    @Test
    fun zipValues_applyTransform() {
        val animating = AnimatedValue.Animating(1) {}
        val notAnimating = AnimatedValue.NotAnimating(2)
        val sum = zip(animating, notAnimating) { a, b -> a + b }
        assertThat(sum.value).isEqualTo(3)
    }

    @Test
    fun zipValues_firstIsAnimating_resultIsAnimating() {
        var stopped = false
        val animating = AnimatedValue.Animating(1) { stopped = true }
        val notAnimating = AnimatedValue.NotAnimating(2)
        val sum = zip(animating, notAnimating) { a, b -> a + b }
        assertThat(sum.isAnimating).isTrue()

        sum.stopAnimating()
        assertThat(stopped).isTrue()
    }

    @Test
    fun zipValues_secondIsAnimating_resultIsAnimating() {
        var stopped = false
        val animating = AnimatedValue.Animating(1) { stopped = true }
        val notAnimating = AnimatedValue.NotAnimating(2)
        val sum = zip(notAnimating, animating) { a, b -> a + b }
        assertThat(sum.isAnimating).isTrue()

        sum.stopAnimating()
        assertThat(stopped).isTrue()
    }

    @Test
    fun zipValues_bothAnimating_resultIsAnimating() {
        var firstStopped = false
        var secondStopped = false
        val first = AnimatedValue.Animating(1) { firstStopped = true }
        val second = AnimatedValue.Animating(2) { secondStopped = true }
        val sum = zip(first, second) { a, b -> a + b }
        assertThat(sum.isAnimating).isTrue()

        sum.stopAnimating()
        assertThat(firstStopped).isTrue()
        assertThat(secondStopped).isTrue()
    }

    @Test
    fun zipValues_neitherAnimating_resultIsNotAnimating() {
        val first = AnimatedValue.NotAnimating(1)
        val second = AnimatedValue.NotAnimating(2)
        val sum = zip(first, second) { a, b -> a + b }
        assertThat(sum.isAnimating).isFalse()
    }

    @Test
    fun mapAnimatedValue_isAnimating() {
        var stopped = false
        val animating = AnimatedValue.Animating(3) { stopped = true }
        val squared = animating.map { it * it }
        assertThat(squared.value).isEqualTo(9)
        assertThat(squared.isAnimating).isTrue()
        squared.stopAnimating()
        assertThat(stopped).isTrue()
    }

    @Test
    fun mapAnimatedValue_notAnimating() {
        val notAnimating = AnimatedValue.NotAnimating(3)
        val squared = notAnimating.map { it * it }
        assertThat(squared.value).isEqualTo(9)
        assertThat(squared.isAnimating).isFalse()
    }

    @Test
    fun flattenAnimatingValue_neitherAnimating() {
        val nested = AnimatedValue.NotAnimating(AnimatedValue.NotAnimating(10))
        val flattened = nested.flatten()
        assertThat(flattened.value).isEqualTo(10)
        assertThat(flattened.isAnimating).isFalse()
    }

    @Test
    fun flattenAnimatingValue_outerAnimating() {
        var stopped = false
        val inner = AnimatedValue.NotAnimating(10)
        val nested = AnimatedValue.Animating(inner) { stopped = true }
        val flattened = nested.flatten()
        assertThat(flattened.value).isEqualTo(10)
        assertThat(flattened.isAnimating).isTrue()
        flattened.stopAnimating()
        assertThat(stopped).isTrue()
    }

    @Test
    fun flattenAnimatingValue_innerAnimating() {
        var stopped = false
        val inner = AnimatedValue.Animating(10) { stopped = true }
        val nested = AnimatedValue.NotAnimating(inner)
        val flattened = nested.flatten()
        assertThat(flattened.value).isEqualTo(10)
        assertThat(flattened.isAnimating).isTrue()
        flattened.stopAnimating()
        assertThat(stopped).isTrue()
    }

    @Test
    fun flattenAnimatingValue_bothAnimating() {
        var innerStopped = false
        var outerStopped = false
        val inner = AnimatedValue.Animating(10) { innerStopped = true }
        val nested = AnimatedValue.Animating(inner) { outerStopped = true }
        val flattened = nested.flatten()
        assertThat(flattened.value).isEqualTo(10)
        assertThat(flattened.isAnimating).isTrue()
        flattened.stopAnimating()
        assertThat(innerStopped).isTrue()
        assertThat(outerStopped).isTrue()
    }
}
