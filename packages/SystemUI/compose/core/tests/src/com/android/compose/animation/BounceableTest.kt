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

package com.android.compose.animation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BounceableTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun bounceable_horizontal() {
        var bounceables by mutableStateOf(List(4) { bounceable(0.dp) })

        rule.setContent {
            Row(Modifier.size(100.dp, 50.dp)) {
                repeat(bounceables.size) { i ->
                    Box(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .bounceable(bounceables, i, orientation = Orientation.Horizontal)
                    )
                }
            }
        }

        // All bounceables have a width of (100dp / bounceables.size) = 25dp and height of 50dp.
        repeat(bounceables.size) { i ->
            rule
                .onNodeWithTag(bounceableTag(i))
                .assertWidthIsEqualTo(25.dp)
                .assertHeightIsEqualTo(50.dp)
                .assertPositionInRootIsEqualTo(i * 25.dp, 0.dp)
        }

        // If all bounceables have the same bounce, it's the same as if they didn't have any.
        bounceables = List(4) { bounceable(10.dp) }
        repeat(bounceables.size) { i ->
            rule
                .onNodeWithTag(bounceableTag(i))
                .assertWidthIsEqualTo(25.dp)
                .assertHeightIsEqualTo(50.dp)
                .assertPositionInRootIsEqualTo(i * 25.dp, 0.dp)
        }

        // Bounce the first and third one.
        bounceables =
            listOf(
                bounceable(bounce = 5.dp),
                bounceable(bounce = 0.dp),
                bounceable(bounce = 10.dp),
                bounceable(bounce = 0.dp),
            )

        // First one has a width of 25dp + 5dp, located in (0, 0).
        rule
            .onNodeWithTag(bounceableTag(0))
            .assertWidthIsEqualTo(30.dp)
            .assertHeightIsEqualTo(50.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)

        // Second one has a width of 25dp - 5dp - 10dp, located in (30, 0).
        rule
            .onNodeWithTag(bounceableTag(1))
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(50.dp)
            .assertPositionInRootIsEqualTo(30.dp, 0.dp)

        // Third one has a width of 25 + 2 * 10dp, located in (40, 0).
        rule
            .onNodeWithTag(bounceableTag(2))
            .assertWidthIsEqualTo(45.dp)
            .assertHeightIsEqualTo(50.dp)
            .assertPositionInRootIsEqualTo(40.dp, 0.dp)

        // First one has a width of 25dp - 10dp, located in (85, 0).
        rule
            .onNodeWithTag(bounceableTag(3))
            .assertWidthIsEqualTo(15.dp)
            .assertHeightIsEqualTo(50.dp)
            .assertPositionInRootIsEqualTo(85.dp, 0.dp)
    }

    @Test
    fun bounceable_vertical() {
        var bounceables by mutableStateOf(List(4) { bounceable(0.dp) })

        rule.setContent {
            Column(Modifier.size(50.dp, 100.dp)) {
                repeat(bounceables.size) { i ->
                    Box(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .bounceable(bounceables, i, Orientation.Vertical)
                    )
                }
            }
        }

        // All bounceables have a height of (100dp / bounceables.size) = 25dp and width of 50dp.
        repeat(bounceables.size) { i ->
            rule
                .onNodeWithTag(bounceableTag(i))
                .assertWidthIsEqualTo(50.dp)
                .assertHeightIsEqualTo(25.dp)
                .assertPositionInRootIsEqualTo(0.dp, i * 25.dp)
        }

        // If all bounceables have the same bounce, it's the same as if they didn't have any.
        bounceables = List(4) { bounceable(10.dp) }
        repeat(bounceables.size) { i ->
            rule
                .onNodeWithTag(bounceableTag(i))
                .assertWidthIsEqualTo(50.dp)
                .assertHeightIsEqualTo(25.dp)
                .assertPositionInRootIsEqualTo(0.dp, i * 25.dp)
        }

        // Bounce the first and third one.
        bounceables =
            listOf(
                bounceable(bounce = 5.dp),
                bounceable(bounce = 0.dp),
                bounceable(bounce = 10.dp),
                bounceable(bounce = 0.dp),
            )

        // First one has a height of 25dp + 5dp, located in (0, 0).
        rule
            .onNodeWithTag(bounceableTag(0))
            .assertWidthIsEqualTo(50.dp)
            .assertHeightIsEqualTo(30.dp)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)

        // Second one has a height of 25dp - 5dp - 10dp, located in (0, 30).
        rule
            .onNodeWithTag(bounceableTag(1))
            .assertWidthIsEqualTo(50.dp)
            .assertHeightIsEqualTo(10.dp)
            .assertPositionInRootIsEqualTo(0.dp, 30.dp)

        // Third one has a height of 25 + 2 * 10dp, located in (0, 40).
        rule
            .onNodeWithTag(bounceableTag(2))
            .assertWidthIsEqualTo(50.dp)
            .assertHeightIsEqualTo(45.dp)
            .assertPositionInRootIsEqualTo(0.dp, 40.dp)

        // First one has a height of 25dp - 10dp, located in (0, 85).
        rule
            .onNodeWithTag(bounceableTag(3))
            .assertWidthIsEqualTo(50.dp)
            .assertHeightIsEqualTo(15.dp)
            .assertPositionInRootIsEqualTo(0.dp, 85.dp)
    }

    private fun bounceable(bounce: Dp): Bounceable {
        return object : Bounceable {
            override val bounce: Dp = bounce
        }
    }

    private fun Modifier.bounceable(
        bounceables: List<Bounceable>,
        i: Int,
        orientation: Orientation,
    ): Modifier {
        val previous = if (i > 0) bounceables[i - 1] else null
        val next = if (i < bounceables.lastIndex) bounceables[i + 1] else null
        return this.bounceable(bounceables[i], previous, next, orientation)
            .testTag(bounceableTag(i))
    }

    private fun bounceableTag(i: Int) = "bounceable$i"
}
