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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.test.assertSizeIsEqualTo
import com.android.compose.ui.util.lerp
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimatedSharedAsStateTest {
    @get:Rule val rule = createComposeRule()

    private data class Values(
        val int: Int,
        val float: Float,
        val dp: Dp,
        val color: Color,
    )

    private fun lerp(start: Values, stop: Values, fraction: Float): Values {
        return Values(
            int = lerp(start.int, stop.int, fraction),
            float = lerp(start.float, stop.float, fraction),
            dp = lerp(start.dp, stop.dp, fraction),
            color = lerp(start.color, stop.color, fraction),
        )
    }

    @Composable
    private fun SceneScope.Foo(
        targetValues: Values,
        onCurrentValueChanged: (Values) -> Unit,
    ) {
        val key = TestElements.Foo
        Element(key, Modifier) {
            val int by animateElementIntAsState(targetValues.int, key = TestValues.Value1)
            val float by animateElementFloatAsState(targetValues.float, key = TestValues.Value2)
            val dp by animateElementDpAsState(targetValues.dp, key = TestValues.Value3)
            val color by animateElementColorAsState(targetValues.color, key = TestValues.Value4)

            content {
                LaunchedEffect(Unit) {
                    snapshotFlow { Values(int, float, dp, color) }.collect(onCurrentValueChanged)
                }
            }
        }
    }

    @Composable
    private fun SceneScope.MovableFoo(
        targetValues: Values,
        onCurrentValueChanged: (Values) -> Unit,
    ) {
        val key = TestElements.Foo
        MovableElement(key = key, Modifier) {
            val int by animateElementIntAsState(targetValues.int, key = TestValues.Value1)
            val float by animateElementFloatAsState(targetValues.float, key = TestValues.Value2)
            val dp by animateElementDpAsState(targetValues.dp, key = TestValues.Value3)
            val color by animateElementColorAsState(targetValues.color, key = TestValues.Value4)

            LaunchedEffect(Unit) {
                snapshotFlow { Values(int, float, dp, color) }.collect(onCurrentValueChanged)
            }
        }
    }

    @Composable
    private fun SceneScope.SceneValues(
        targetValues: Values,
        onCurrentValueChanged: (Values) -> Unit,
    ) {
        val int by animateSceneIntAsState(targetValues.int, key = TestValues.Value1)
        val float by animateSceneFloatAsState(targetValues.float, key = TestValues.Value2)
        val dp by animateSceneDpAsState(targetValues.dp, key = TestValues.Value3)
        val color by animateSceneColorAsState(targetValues.color, key = TestValues.Value4)

        LaunchedEffect(Unit) {
            snapshotFlow { Values(int, float, dp, color) }.collect(onCurrentValueChanged)
        }
    }

    @Test
    fun animateElementValues() {
        val fromValues = Values(int = 0, float = 0f, dp = 0.dp, color = Color.Red)
        val toValues = Values(int = 100, float = 100f, dp = 100.dp, color = Color.Blue)

        var lastValueInFrom = fromValues
        var lastValueInTo = toValues

        rule.testTransition(
            fromSceneContent = {
                Foo(targetValues = fromValues, onCurrentValueChanged = { lastValueInFrom = it })
            },
            toSceneContent = {
                Foo(targetValues = toValues, onCurrentValueChanged = { lastValueInTo = it })
            },
            transition = {
                // The transition lasts 64ms = 4 frames.
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
            },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                assertThat(lastValueInFrom).isEqualTo(fromValues)

                // to was not composed yet, so lastValueInTo was not set yet.
                assertThat(lastValueInTo).isEqualTo(toValues)
            }

            at(16) {
                // Given that we use Modifier.element() here, animateSharedXAsState is composed in
                // both scenes and values should be interpolated with the transition fraction.
                val expectedValues = lerp(fromValues, toValues, fraction = 0.25f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(32) {
                val expectedValues = lerp(fromValues, toValues, fraction = 0.5f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(48) {
                val expectedValues = lerp(fromValues, toValues, fraction = 0.75f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            after {
                assertThat(lastValueInFrom).isEqualTo(toValues)
                assertThat(lastValueInTo).isEqualTo(toValues)
            }
        }
    }

    @Test
    fun movableAnimateSharedValues() {
        val fromValues = Values(int = 0, float = 0f, dp = 0.dp, color = Color.Red)
        val toValues = Values(int = 100, float = 100f, dp = 100.dp, color = Color.Blue)

        var lastValueInFrom = fromValues
        var lastValueInTo = toValues

        rule.testTransition(
            fromSceneContent = {
                MovableFoo(
                    targetValues = fromValues,
                    onCurrentValueChanged = { lastValueInFrom = it }
                )
            },
            toSceneContent = {
                MovableFoo(targetValues = toValues, onCurrentValueChanged = { lastValueInTo = it })
            },
            transition = {
                // The transition lasts 64ms = 4 frames.
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
            },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                assertThat(lastValueInFrom).isEqualTo(fromValues)

                // to was not composed yet, so lastValueInTo was not set yet.
                assertThat(lastValueInTo).isEqualTo(toValues)
            }

            at(16) {
                assertThat(lastValueInFrom).isEqualTo(lerp(fromValues, toValues, fraction = 0.25f))
                assertThat(lastValueInTo).isEqualTo(lerp(fromValues, toValues, fraction = 0.25f))
            }

            at(32) {
                assertThat(lastValueInFrom).isEqualTo(lerp(fromValues, toValues, fraction = 0.5f))
                assertThat(lastValueInTo).isEqualTo(lerp(fromValues, toValues, fraction = 0.5f))
            }

            at(48) {
                assertThat(lastValueInFrom).isEqualTo(lerp(fromValues, toValues, fraction = 0.75f))
                assertThat(lastValueInTo).isEqualTo(lerp(fromValues, toValues, fraction = 0.75f))
            }

            after {
                assertThat(lastValueInFrom).isEqualTo(toValues)
                assertThat(lastValueInTo).isEqualTo(toValues)
            }
        }
    }

    @Test
    fun animateSceneValues() {
        val fromValues = Values(int = 0, float = 0f, dp = 0.dp, color = Color.Red)
        val toValues = Values(int = 100, float = 100f, dp = 100.dp, color = Color.Blue)

        var lastValueInFrom = fromValues
        var lastValueInTo = toValues

        rule.testTransition(
            fromSceneContent = {
                SceneValues(
                    targetValues = fromValues,
                    onCurrentValueChanged = { lastValueInFrom = it }
                )
            },
            toSceneContent = {
                SceneValues(targetValues = toValues, onCurrentValueChanged = { lastValueInTo = it })
            },
            transition = {
                // The transition lasts 64ms = 4 frames.
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
            },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                assertThat(lastValueInFrom).isEqualTo(fromValues)

                // to was not composed yet, so lastValueInTo was not set yet.
                assertThat(lastValueInTo).isEqualTo(toValues)
            }

            at(16) {
                // Given that we use scene values here, animateSceneXAsState is composed in both
                // scenes and values should be interpolated with the transition fraction.
                val expectedValues = lerp(fromValues, toValues, fraction = 0.25f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(32) {
                val expectedValues = lerp(fromValues, toValues, fraction = 0.5f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(48) {
                val expectedValues = lerp(fromValues, toValues, fraction = 0.75f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            after {
                assertThat(lastValueInFrom).isEqualTo(toValues)
                assertThat(lastValueInTo).isEqualTo(toValues)
            }
        }
    }

    @Test
    fun readingAnimatedStateValueDuringCompositionThrows() {
        assertThrows(IllegalStateException::class.java) {
            rule.testTransition(
                fromSceneContent = { animateSceneIntAsState(0, TestValues.Value1).value },
                toSceneContent = {},
                transition = {},
            ) {}
        }
    }

    @Test
    fun readingAnimatedStateValueDuringCompositionIsStillPossible() {
        @Composable
        fun SceneScope.Bar(targetSize: Dp, modifier: Modifier = Modifier) {
            val size = animateSceneDpAsState(targetSize, TestValues.Value1)
            Box(modifier.element(TestElements.Bar).size(size.valueOrNull ?: targetSize))
        }

        rule.testTransition(
            fromSceneContent = { Bar(targetSize = 10.dp) },
            toSceneContent = { Bar(targetSize = 50.dp) },
            transition = {
                // The transition lasts 64ms = 4 frames.
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
            },
        ) {
            before { onElement(TestElements.Bar).assertSizeIsEqualTo(10.dp, 10.dp) }

            at(16) {
                onElement(TestElements.Bar, TestScenes.SceneB).assertSizeIsEqualTo(20.dp, 20.dp)
            }

            at(32) {
                onElement(TestElements.Bar, TestScenes.SceneB).assertSizeIsEqualTo(30.dp, 30.dp)
            }

            at(48) {
                onElement(TestElements.Bar, TestScenes.SceneB).assertSizeIsEqualTo(40.dp, 40.dp)
            }

            after {
                onElement(TestElements.Bar, TestScenes.SceneB).assertSizeIsEqualTo(50.dp, 50.dp)
            }
        }
    }
}
