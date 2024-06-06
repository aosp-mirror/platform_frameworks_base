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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.TestScenes.SceneD
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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
            fromScene = SceneA,
            toScene = SceneB,
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
            fromScene = SceneA,
            toScene = SceneB,
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
            fromScene = SceneA,
            toScene = SceneB,
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
        fun SceneScope.SceneValuesDuringComposition(
            targetValues: Values,
            onCurrentValueChanged: (Values) -> Unit,
        ) {
            val int by
                animateSceneIntAsState(targetValues.int, key = TestValues.Value1)
                    .unsafeCompositionState(targetValues.int)
            val float by
                animateSceneFloatAsState(targetValues.float, key = TestValues.Value2)
                    .unsafeCompositionState(targetValues.float)
            val dp by
                animateSceneDpAsState(targetValues.dp, key = TestValues.Value3)
                    .unsafeCompositionState(targetValues.dp)
            val color by
                animateSceneColorAsState(targetValues.color, key = TestValues.Value4)
                    .unsafeCompositionState(targetValues.color)

            val values = Values(int, float, dp, color)
            SideEffect { onCurrentValueChanged(values) }
        }

        val fromValues = Values(int = 0, float = 0f, dp = 0.dp, color = Color.Red)
        val toValues = Values(int = 100, float = 100f, dp = 100.dp, color = Color.Blue)

        var lastValueInFrom = fromValues
        var lastValueInTo = toValues

        rule.testTransition(
            fromSceneContent = {
                SceneValuesDuringComposition(
                    targetValues = fromValues,
                    onCurrentValueChanged = { lastValueInFrom = it },
                )
            },
            toSceneContent = {
                SceneValuesDuringComposition(
                    targetValues = toValues,
                    onCurrentValueChanged = { lastValueInTo = it },
                )
            },
            transition = {
                // The transition lasts 64ms = 4 frames.
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
            },
        ) {
            before {
                assertThat(lastValueInFrom).isEqualTo(fromValues)

                // to was not composed yet, so lastValueInTo was not set yet.
                assertThat(lastValueInTo).isEqualTo(toValues)
            }

            at(16) {
                // Because we are using unsafeCompositionState(), values are one frame behind their
                // expected progress so at this first frame we are at progress = 0% instead of 25%.
                val expectedValues = lerp(fromValues, toValues, fraction = 0f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(32) {
                // One frame behind, so 25% instead of 50%.
                val expectedValues = lerp(fromValues, toValues, fraction = 0.25f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            at(48) {
                // One frame behind, so 50% instead of 75%.
                val expectedValues = lerp(fromValues, toValues, fraction = 0.5f)
                assertThat(lastValueInFrom).isEqualTo(expectedValues)
                assertThat(lastValueInTo).isEqualTo(expectedValues)
            }

            after {
                // from should have been last composed at progress = 100% before it is removed from
                // composition, but given that we are one frame behind the last values are stuck at
                // 75%.
                assertThat(lastValueInFrom).isEqualTo(lerp(fromValues, toValues, fraction = 0.75f))

                // The after {} block resumes the clock and will run as many frames as necessary so
                // that the application is idle, so the toScene settle to the idle state and to the
                // final values.
                assertThat(lastValueInTo).isEqualTo(toValues)
            }
        }
    }

    @Test
    fun animatedValueIsUsingLastTransition() = runTest {
        val state =
            rule.runOnUiThread { MutableSceneTransitionLayoutStateImpl(SceneA, transitions {}) }

        val foo = ValueKey("foo")
        val bar = ValueKey("bar")
        val lastValues = mutableMapOf<ValueKey, MutableMap<SceneKey, Float>>()

        @Composable
        fun SceneScope.animateFloat(value: Float, key: ValueKey) {
            val animatedValue = animateSceneFloatAsState(value, key)
            LaunchedEffect(animatedValue) {
                snapshotFlow { animatedValue.value }
                    .collect { lastValues.getOrPut(key) { mutableMapOf() }[sceneKey] = it }
            }
        }

        rule.setContent {
            SceneTransitionLayout(state) {
                // foo goes from 0f to 100f in A => B.
                scene(SceneA) { animateFloat(0f, foo) }
                scene(SceneB) { animateFloat(100f, foo) }

                // bar goes from 0f to 10f in C => D.
                scene(SceneC) { animateFloat(0f, bar) }
                scene(SceneD) { animateFloat(10f, bar) }
            }
        }

        rule.runOnUiThread {
            // A => B is at 30%.
            state.startTransition(
                transition(
                    from = SceneA,
                    to = SceneB,
                    progress = { 0.3f },
                    onFinish = neverFinish(),
                )
            )

            // C => D is at 70%.
            state.startTransition(transition(from = SceneC, to = SceneD, progress = { 0.7f }))
        }
        rule.waitForIdle()

        assertThat(lastValues[foo]?.get(SceneA)).isWithin(0.001f).of(30f)
        assertThat(lastValues[foo]?.get(SceneB)).isWithin(0.001f).of(30f)
        assertThat(lastValues[foo]?.get(SceneC)).isNull()
        assertThat(lastValues[foo]?.get(SceneD)).isNull()

        assertThat(lastValues[bar]?.get(SceneA)).isNull()
        assertThat(lastValues[bar]?.get(SceneB)).isNull()
        assertThat(lastValues[bar]?.get(SceneC)).isWithin(0.001f).of(7f)
        assertThat(lastValues[bar]?.get(SceneD)).isWithin(0.001f).of(7f)
    }
}
