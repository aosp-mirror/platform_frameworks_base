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

package com.android.systemui.statusbar.notification

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.notifications.ui.composable.NotificationScrimNestedScrollConnection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationScrimNestedScrollConnectionTest : SysuiTestCase() {
    private var isStarted = false
    private var scrimOffset = 0f
    private var contentHeight = 0f
    private var isCurrentGestureOverscroll = false

    private val scrollConnection =
        NotificationScrimNestedScrollConnection(
            scrimOffset = { scrimOffset },
            snapScrimOffset = { _ -> },
            animateScrimOffset = { _ -> },
            minScrimOffset = { MIN_SCRIM_OFFSET },
            maxScrimOffset = MAX_SCRIM_OFFSET,
            contentHeight = { contentHeight },
            minVisibleScrimHeight = { MIN_VISIBLE_SCRIM_HEIGHT },
            isCurrentGestureOverscroll = { isCurrentGestureOverscroll },
            onStart = { isStarted = true },
            onStop = { isStarted = false },
        )

    @Test
    fun onScrollUp_canStartPreScroll_contentNotExpanded_ignoreScroll() = runTest {
        contentHeight = COLLAPSED_CONTENT_HEIGHT

        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = Offset(x = 0f, y = -1f),
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(false)
    }

    @Test
    fun onScrollUp_canStartPreScroll_contentExpandedAtMinOffset_ignoreScroll() = runTest {
        contentHeight = EXPANDED_CONTENT_HEIGHT
        scrimOffset = MIN_SCRIM_OFFSET

        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = Offset(x = 0f, y = -1f),
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(false)
    }

    @Test
    fun onScrollUp_canStartPreScroll_contentExpanded_consumeScroll() = runTest {
        contentHeight = EXPANDED_CONTENT_HEIGHT

        val availableOffset = Offset(x = 0f, y = -1f)
        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = availableOffset,
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(availableOffset)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun onScrollUp_canStartPreScroll_contentExpanded_consumeScrollWithRemainder() = runTest {
        contentHeight = EXPANDED_CONTENT_HEIGHT
        scrimOffset = MIN_SCRIM_OFFSET + 1

        val availableOffset = Offset(x = 0f, y = -2f)
        val consumableOffset = Offset(x = 0f, y = -1f)
        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = availableOffset,
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(consumableOffset)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun onScrollUp_canStartPostScroll_ignoreScroll() = runTest {
        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = -1f),
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(false)
    }

    @Test
    fun onScrollDown_canStartPreScroll_ignoreScroll() = runTest {
        val offsetConsumed =
            scrollConnection.onPreScroll(
                available = Offset(x = 0f, y = 1f),
                source = NestedScrollSource.Drag,
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(false)
    }

    @Test
    fun onScrollDown_canStartPostScroll_consumeScroll() = runTest {
        scrimOffset = MIN_SCRIM_OFFSET

        val availableOffset = Offset(x = 0f, y = 1f)
        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = availableOffset,
                source = NestedScrollSource.Drag
            )

        assertThat(offsetConsumed).isEqualTo(availableOffset)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun onScrollDown_canStartPostScroll_consumeScrollWithRemainder() = runTest {
        scrimOffset = MAX_SCRIM_OFFSET - 1

        val availableOffset = Offset(x = 0f, y = 2f)
        val consumableOffset = Offset(x = 0f, y = 1f)
        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = availableOffset,
                source = NestedScrollSource.Drag
            )

        assertThat(offsetConsumed).isEqualTo(consumableOffset)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun canStartPostScroll_atMaxOffset_ignoreScroll() = runTest {
        scrimOffset = MAX_SCRIM_OFFSET

        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = 1f),
                source = NestedScrollSource.Drag
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(false)
    }

    @Test
    fun canStartPostScroll_externalOverscrollGesture_startButIgnoreScroll() = runTest {
        scrimOffset = MAX_SCRIM_OFFSET
        isCurrentGestureOverscroll = true

        val offsetConsumed =
            scrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = Offset(x = 0f, y = 1f),
                source = NestedScrollSource.Drag
            )

        assertThat(offsetConsumed).isEqualTo(Offset.Zero)
        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun canContinueScroll_inBetweenMinMaxOffset_true() = runTest {
        scrimOffset = (MIN_SCRIM_OFFSET + MAX_SCRIM_OFFSET) / 2f
        contentHeight = EXPANDED_CONTENT_HEIGHT
        scrollConnection.onPreScroll(
            available = Offset(x = 0f, y = -1f),
            source = NestedScrollSource.Drag
        )

        assertThat(isStarted).isEqualTo(true)

        scrollConnection.onPreScroll(
            available = Offset(x = 0f, y = 1f),
            source = NestedScrollSource.Drag
        )

        assertThat(isStarted).isEqualTo(true)
    }

    @Test
    fun canContinueScroll_atMaxOffset_false() = runTest {
        scrimOffset = MAX_SCRIM_OFFSET
        contentHeight = EXPANDED_CONTENT_HEIGHT
        scrollConnection.onPreScroll(
            available = Offset(x = 0f, y = -1f),
            source = NestedScrollSource.Drag
        )

        assertThat(isStarted).isEqualTo(true)

        scrollConnection.onPreScroll(
            available = Offset(x = 0f, y = 1f),
            source = NestedScrollSource.Drag
        )

        assertThat(isStarted).isEqualTo(false)
    }

    companion object {
        const val MIN_SCRIM_OFFSET = -100f
        const val MAX_SCRIM_OFFSET = 0f

        const val EXPANDED_CONTENT_HEIGHT = 200f
        const val COLLAPSED_CONTENT_HEIGHT = 40f

        const val MIN_VISIBLE_SCRIM_HEIGHT = 50f
    }
}
