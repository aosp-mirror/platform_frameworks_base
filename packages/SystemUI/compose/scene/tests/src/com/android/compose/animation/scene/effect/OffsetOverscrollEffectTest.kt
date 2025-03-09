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

package com.android.compose.animation.scene.effect

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.overscroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.properties.Delegates
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OffsetOverscrollEffectTest {
    @get:Rule val rule = createComposeRule()

    private val BOX_TAG = "box"

    private data class LayoutInfo(val layoutSize: Dp, val touchSlop: Float, val density: Density) {
        fun expectedOffset(currentOffset: Dp): Dp {
            return with(density) {
                OffsetOverscrollEffect.computeOffset(this, currentOffset.toPx()).toDp()
            }
        }
    }

    private fun setupOverscrollableBox(
        scrollableOrientation: Orientation,
        overscrollEffectOrientation: Orientation = scrollableOrientation,
    ): LayoutInfo {
        val layoutSize: Dp = 200.dp
        var touchSlop: Float by Delegates.notNull()
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        lateinit var density: Density
        rule.setContent {
            density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            val overscrollEffect = rememberOffsetOverscrollEffect(overscrollEffectOrientation)

            Box(
                Modifier.overscroll(overscrollEffect)
                    // A scrollable that does not consume the scroll gesture.
                    .scrollable(
                        state = rememberScrollableState { 0f },
                        orientation = scrollableOrientation,
                        overscrollEffect = overscrollEffect,
                    )
                    .size(layoutSize)
                    .testTag(BOX_TAG)
            )
        }
        return LayoutInfo(layoutSize, touchSlop, density)
    }

    @Test
    fun applyVerticalOffset_duringVerticalOverscroll() {
        val info = setupOverscrollableBox(scrollableOrientation = Orientation.Vertical)

        rule.onNodeWithTag(BOX_TAG).assertTopPositionInRootIsEqualTo(0.dp)

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, info.touchSlop + info.layoutSize.toPx()), delayMillis = 1_000)
        }

        rule
            .onNodeWithTag(BOX_TAG)
            .assertTopPositionInRootIsEqualTo(info.expectedOffset(info.layoutSize))
    }

    @Test
    fun applyNoOffset_duringHorizontalOverscroll() {
        val info =
            setupOverscrollableBox(
                scrollableOrientation = Orientation.Vertical,
                overscrollEffectOrientation = Orientation.Horizontal,
            )

        rule.onNodeWithTag(BOX_TAG).assertTopPositionInRootIsEqualTo(0.dp)

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(info.touchSlop + info.layoutSize.toPx(), 0f), delayMillis = 1_000)
        }

        rule.onNodeWithTag(BOX_TAG).assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun backToZero_afterOverscroll() {
        val info = setupOverscrollableBox(scrollableOrientation = Orientation.Vertical)

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, info.touchSlop + info.layoutSize.toPx()), delayMillis = 1_000)
        }

        rule
            .onNodeWithTag(BOX_TAG)
            .assertTopPositionInRootIsEqualTo(info.expectedOffset(info.layoutSize))

        rule.onRoot().performTouchInput { up() }

        rule.onNodeWithTag(BOX_TAG).assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun offsetOverscroll_followTheTouchPointer() {
        val info = setupOverscrollableBox(scrollableOrientation = Orientation.Vertical)

        // First gesture, drag down.
        rule.onRoot().performTouchInput {
            down(center)
            // A full screen scroll.
            moveBy(Offset(0f, info.touchSlop + info.layoutSize.toPx()), delayMillis = 1_000)
        }
        rule
            .onNodeWithTag(BOX_TAG)
            .assertTopPositionInRootIsEqualTo(info.expectedOffset(info.layoutSize))

        rule.onRoot().performTouchInput {
            // Reduced by half.
            moveBy(Offset(0f, -info.layoutSize.toPx() / 2), delayMillis = 1_000)
        }
        rule
            .onNodeWithTag(BOX_TAG)
            .assertTopPositionInRootIsEqualTo(info.expectedOffset(info.layoutSize / 2))

        rule.onRoot().performTouchInput { up() }
        // Animate back to 0.
        rule.onNodeWithTag(BOX_TAG).assertTopPositionInRootIsEqualTo(0.dp)

        // Second gesture, drag up.
        rule.onRoot().performTouchInput {
            down(center)
            // A full screen scroll.
            moveBy(Offset(0f, -info.touchSlop - info.layoutSize.toPx()), delayMillis = 1_000)
        }
        rule
            .onNodeWithTag(BOX_TAG)
            .assertTopPositionInRootIsEqualTo(info.expectedOffset(-info.layoutSize))
    }
}
