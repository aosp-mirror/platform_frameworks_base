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

package com.android.compose.nestedscroll

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LargeTopAppBarNestedScrollConnectionTest(testCase: TestCase) {
    val scrollSource = testCase.scrollSource

    private var height = 0f

    private fun buildScrollConnection(heightRange: ClosedFloatingPointRange<Float>) =
        LargeTopAppBarNestedScrollConnection(
            height = { height },
            onHeightChanged = { height = it },
            minHeight = { heightRange.start },
            maxHeight = { heightRange.endInclusive },
        )

    private fun NestedScrollConnection.scroll(
        available: Offset,
        consumedByScroll: Offset = Offset.Zero,
    ) {
        val consumedByPreScroll = onPreScroll(available = available, source = scrollSource)
        val consumed = consumedByPreScroll + consumedByScroll
        onPostScroll(consumed = consumed, available = available - consumed, source = scrollSource)
    }

    @Test
    fun onScrollUp_consumeHeightFirst() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        val offsetConsumed =
            scrollConnection.onPreScroll(available = Offset(x = 0f, y = -1f), source = scrollSource)

        // It can decrease by 1 the height
        assertThat(offsetConsumed).isEqualTo(Offset(0f, -1f))
        assertThat(height).isEqualTo(0f)
    }

    @Test
    fun onScrollUpAfterContentScrolled_ignoreUpEvent() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        // scroll down consumed by a child
        scrollConnection.scroll(available = Offset(0f, 1f), consumedByScroll = Offset(0f, 1f))

        val offsetConsumed =
            scrollConnection.onPreScroll(available = Offset(x = 0f, y = -1f), source = scrollSource)

        // It should ignore all onPreScroll events
        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(height).isEqualTo(1f)
    }

    @Test
    fun onScrollUpAfterContentReturnedToZero_consumeHeight() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        // scroll down consumed by a child
        scrollConnection.scroll(available = Offset(0f, 1f), consumedByScroll = Offset(0f, 1f))

        // scroll up consumed by a child, the child is in its original position
        scrollConnection.scroll(available = Offset(0f, -1f), consumedByScroll = Offset(0f, -1f))

        val offsetConsumed =
            scrollConnection.onPreScroll(available = Offset(x = 0f, y = -1f), source = scrollSource)

        // It should ignore all onPreScroll events
        assertThat(offsetConsumed).isEqualTo(Offset(0f, -1f))
        assertThat(height).isEqualTo(0f)
    }

    @Test
    fun onScrollUp_consumeDownToMin() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 0f

        val offsetConsumed =
            scrollConnection.onPreScroll(available = Offset(x = 0f, y = -1f), source = scrollSource)

        // It should not change the height (already at min)
        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(height).isEqualTo(0f)
    }

    @Test
    fun onScrollUp_ignorePostScroll() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = -1f),
                source = scrollSource
            )

        // It should ignore all onPostScroll events
        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(height).isEqualTo(1f)
    }

    @Test
    fun onScrollDown_allowConsumeContentFirst() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        val offsetConsumed =
            scrollConnection.onPreScroll(available = Offset(x = 0f, y = 1f), source = scrollSource)

        // It should ignore all onPreScroll events
        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(height).isEqualTo(1f)
    }

    @Test
    fun onScrollDown_consumeHeightPostScroll() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f

        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = 1f),
                source = scrollSource
            )

        // It can increase by 1 the height
        assertThat(offsetConsumed).isEqualTo(Offset(0f, 1f))
        assertThat(height).isEqualTo(2f)
    }

    @Test
    fun onScrollDownAfterPostScroll_consumeHeightPreScroll() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 1f
        scrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(x = 0f, y = 0.5f),
            source = scrollSource
        )

        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = Offset(x = 0f, y = 0.5f),
                source = scrollSource
            )
        assertThat(offsetConsumed).isEqualTo(Offset(0f, 0.5f))

        // It can increase by 1 (0.5f + 0.5f) the height
        assertThat(height).isEqualTo(2f)
    }

    @Test
    fun onScrollDown_consumeUpToMax() {
        val scrollConnection = buildScrollConnection(heightRange = 0f..2f)
        height = 2f

        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = 1f),
                source = scrollSource
            )

        // It should not change the height (already at max)
        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(height).isEqualTo(2f)
    }

    // NestedScroll Source is a value/inline class and must be wrapped in a parameterized test
    // https://youtrack.jetbrains.com/issue/KT-35523/Parameterized-JUnit-tests-with-inline-classes-throw-IllegalArgumentException
    data class TestCase(val scrollSource: NestedScrollSource) {
        override fun toString() = scrollSource.toString()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): List<TestCase> =
            listOf(
                TestCase(NestedScrollSource.Drag),
                TestCase(NestedScrollSource.Fling),
                TestCase(NestedScrollSource.Wheel),
            )
    }
}
