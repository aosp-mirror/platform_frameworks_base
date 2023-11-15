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

package com.android.compose.animation.scene

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.intermediateLayout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementTest {
    @get:Rule val rule = createComposeRule()

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun SceneScope.Element(
        key: ElementKey,
        size: Dp,
        offset: Dp,
        modifier: Modifier = Modifier,
        onLayout: () -> Unit = {},
        onPlacement: () -> Unit = {},
    ) {
        Box(
            modifier
                .offset(offset)
                .element(key)
                .intermediateLayout { measurable, constraints ->
                    onLayout()
                    val placement = measurable.measure(constraints)
                    layout(placement.width, placement.height) {
                        onPlacement()
                        placement.place(0, 0)
                    }
                }
                .size(size)
        )
    }

    @Test
    fun staticElements_noLayout_noPlacement() {
        val nFrames = 20
        val layoutSize = 100.dp
        val elementSize = 50.dp
        val elementOffset = 20.dp

        var fooLayouts = 0
        var fooPlacements = 0
        var barLayouts = 0
        var barPlacements = 0

        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        elementOffset,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )

                    // Transformed element
                    Element(
                        TestElements.Bar,
                        elementSize,
                        elementOffset,
                        onLayout = { barLayouts++ },
                        onPlacement = { barPlacements++ },
                    )
                }
            },
            toSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(TestElements.Foo, elementSize, elementOffset)
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // no-op transformations.
                translate(TestElements.Bar, x = 0.dp, y = 0.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var numberOfLayoutsAfterOneAnimationFrame = 0
            var numberOfPlacementsAfterOneAnimationFrame = 0

            fun assertNumberOfLayoutsAndPlacements() {
                assertThat(fooLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(fooPlacements).isEqualTo(numberOfPlacementsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(barPlacements).isEqualTo(numberOfPlacementsAfterOneAnimationFrame)
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                numberOfLayoutsAfterOneAnimationFrame = fooLayouts
                numberOfPlacementsAfterOneAnimationFrame = fooPlacements
            }
            repeat(nFrames - 2) { i ->
                // Ensure that all animation frames (except the final one) don't relayout or replace
                // static (shared or transformed) elements.
                at(32L + i * 16) { assertNumberOfLayoutsAndPlacements() }
            }
        }
    }

    @Test
    fun onlyMovingElements_noLayout_onlyPlacement() {
        val nFrames = 20
        val layoutSize = 100.dp
        val elementSize = 50.dp

        var fooLayouts = 0
        var fooPlacements = 0
        var barLayouts = 0
        var barPlacements = 0

        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(
                        TestElements.Foo,
                        elementSize,
                        offset = 0.dp,
                        onLayout = { fooLayouts++ },
                        onPlacement = { fooPlacements++ },
                    )

                    // Transformed element
                    Element(
                        TestElements.Bar,
                        elementSize,
                        offset = 0.dp,
                        onLayout = { barLayouts++ },
                        onPlacement = { barPlacements++ },
                    )
                }
            },
            toSceneContent = {
                Box(Modifier.size(layoutSize)) {
                    // Shared element.
                    Element(TestElements.Foo, elementSize, offset = 20.dp)
                }
            },
            transition = {
                spec = tween(nFrames * 16)

                // Only translate Bar.
                translate(TestElements.Bar, x = 20.dp, y = 20.dp)
                scaleSize(TestElements.Bar, width = 1f, height = 1f)
            },
        ) {
            var numberOfLayoutsAfterOneAnimationFrame = 0
            var lastNumberOfPlacements = 0

            fun assertNumberOfLayoutsAndPlacements() {
                // The number of layouts have not changed.
                assertThat(fooLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)
                assertThat(barLayouts).isEqualTo(numberOfLayoutsAfterOneAnimationFrame)

                // The number of placements have increased.
                assertThat(fooPlacements).isGreaterThan(lastNumberOfPlacements)
                assertThat(barPlacements).isGreaterThan(lastNumberOfPlacements)
                lastNumberOfPlacements = fooPlacements
            }

            at(16) {
                // Capture the number of layouts and placements that happened after 1 animation
                // frame.
                numberOfLayoutsAfterOneAnimationFrame = fooLayouts
                lastNumberOfPlacements = fooPlacements
            }
            repeat(nFrames - 2) { i ->
                // Ensure that all animation frames (except the final one) only replaced the
                // elements.
                at(32L + i * 16) { assertNumberOfLayoutsAndPlacements() }
            }
        }
    }
}
