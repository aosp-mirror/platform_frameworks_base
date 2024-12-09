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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OffsetOverscrollEffectTest {
    @get:Rule val rule = createComposeRule()

    private fun expectedOffset(currentOffset: Dp, density: Density): Dp {
        return with(density) {
            OffsetOverscrollEffect.computeOffset(this, currentOffset.toPx()).toDp()
        }
    }

    @Test
    fun applyVerticalOffset_duringVerticalOverscroll() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        lateinit var density: Density
        val layoutSize = 200.dp

        rule.setContent {
            density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            val overscrollEffect = rememberOffsetOverscrollEffect(Orientation.Vertical)

            Box(
                Modifier.overscroll(overscrollEffect)
                    // A scrollable that does not consume the scroll gesture.
                    .scrollable(
                        state = rememberScrollableState { 0f },
                        orientation = Orientation.Vertical,
                        overscrollEffect = overscrollEffect,
                    )
                    .size(layoutSize)
                    .testTag("box")
            )
        }

        val onBox = rule.onNodeWithTag("box")

        onBox.assertTopPositionInRootIsEqualTo(0.dp)

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, touchSlop + layoutSize.toPx()), delayMillis = 1_000)
        }

        onBox.assertTopPositionInRootIsEqualTo(expectedOffset(layoutSize, density))
    }

    @Test
    fun applyNoOffset_duringHorizontalOverscroll() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        val layoutSize = 200.dp

        rule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            val overscrollEffect = rememberOffsetOverscrollEffect(Orientation.Vertical)

            Box(
                Modifier.overscroll(overscrollEffect)
                    // A scrollable that does not consume the scroll gesture.
                    .scrollable(
                        state = rememberScrollableState { 0f },
                        orientation = Orientation.Horizontal,
                        overscrollEffect = overscrollEffect,
                    )
                    .size(layoutSize)
                    .testTag("box")
            )
        }

        val onBox = rule.onNodeWithTag("box")

        onBox.assertTopPositionInRootIsEqualTo(0.dp)

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(touchSlop + layoutSize.toPx(), 0f), delayMillis = 1_000)
        }

        onBox.assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun backToZero_afterOverscroll() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        lateinit var density: Density
        val layoutSize = 200.dp

        rule.setContent {
            density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            val overscrollEffect = rememberOffsetOverscrollEffect(Orientation.Vertical)

            Box(
                Modifier.overscroll(overscrollEffect)
                    // A scrollable that does not consume the scroll gesture.
                    .scrollable(
                        state = rememberScrollableState { 0f },
                        orientation = Orientation.Vertical,
                        overscrollEffect = overscrollEffect,
                    )
                    .size(layoutSize)
                    .testTag("box")
            )
        }

        val onBox = rule.onNodeWithTag("box")

        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, touchSlop + layoutSize.toPx()), delayMillis = 1_000)
        }

        onBox.assertTopPositionInRootIsEqualTo(expectedOffset(layoutSize, density))

        rule.onRoot().performTouchInput { up() }

        onBox.assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun offsetOverscroll_followTheTouchPointer() {
        // The draggable touch slop, i.e. the min px distance a touch pointer must move before it is
        // detected as a drag event.
        var touchSlop = 0f
        lateinit var density: Density
        val layoutSize = 200.dp

        rule.setContent {
            density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            val overscrollEffect = rememberOffsetOverscrollEffect(Orientation.Vertical)

            Box(
                Modifier.overscroll(overscrollEffect)
                    // A scrollable that does not consume the scroll gesture.
                    .scrollable(
                        state = rememberScrollableState { 0f },
                        orientation = Orientation.Vertical,
                        overscrollEffect = overscrollEffect,
                    )
                    .size(layoutSize)
                    .testTag("box")
            )
        }

        val onBox = rule.onNodeWithTag("box")

        rule.onRoot().performTouchInput {
            down(center)
            // A full screen scroll.
            moveBy(Offset(0f, touchSlop + layoutSize.toPx()), delayMillis = 1_000)
        }
        onBox.assertTopPositionInRootIsEqualTo(expectedOffset(layoutSize, density))

        rule.onRoot().performTouchInput {
            // Reduced by half.
            moveBy(Offset(0f, -layoutSize.toPx() / 2), delayMillis = 1_000)
        }
        onBox.assertTopPositionInRootIsEqualTo(expectedOffset(layoutSize / 2, density))
    }
}
