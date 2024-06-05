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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.compose.ComposeRecordingSpec
import platform.test.motion.compose.ComposeToolkit
import platform.test.motion.compose.MotionControl
import platform.test.motion.compose.feature
import platform.test.motion.compose.recordMotion
import platform.test.motion.golden.FeatureCapture
import platform.test.motion.golden.TimeSeriesCaptureScope

@DslMarker annotation class TransitionTestDsl

@TransitionTestDsl
interface TransitionTestBuilder {
    /**
     * Assert on the state of the layout before the transition starts.
     *
     * This should be called maximum once, before [at] or [after] is called.
     */
    fun before(builder: TransitionTestAssertionScope.() -> Unit)

    /**
     * Assert on the state of the layout during the transition at [timestamp].
     *
     * This should be called after [before] is called and before [after] is called. Successive calls
     * to [at] must be called with increasing [timestamp].
     *
     * Important: [timestamp] must be a multiple of 16 (the duration of a frame on the JVM/Android).
     * There is no intermediary state between `t` and `t + 16` , so testing transitions outside of
     * `t = 0`, `t = 16`, `t = 32`, etc does not make sense.
     */
    fun at(timestamp: Long, builder: TransitionTestAssertionScope.() -> Unit)

    /**
     * Assert on the state of the layout after the transition finished.
     *
     * This should be called maximum once, after [before] or [at] is called.
     */
    fun after(builder: TransitionTestAssertionScope.() -> Unit)
}

@TransitionTestDsl
interface TransitionTestAssertionScope {
    /**
     * Assert on [element].
     *
     * Note that presence/value assertions on the returned [SemanticsNodeInteraction] will fail if 0
     * or more than 1 elements matched [element]. If you need to assert on a shared element that
     * will be present multiple times in the layout during transitions, specify the [scene] in which
     * you are matching.
     */
    fun onElement(element: ElementKey, scene: SceneKey? = null): SemanticsNodeInteraction
}

/**
 * Test the transition between [fromSceneContent] and [toSceneContent] at different points in time.
 *
 * @sample com.android.compose.animation.scene.transformation.TranslateTest
 */
fun ComposeContentTestRule.testTransition(
    fromSceneContent: @Composable SceneScope.() -> Unit,
    toSceneContent: @Composable SceneScope.() -> Unit,
    transition: TransitionBuilder.() -> Unit,
    layoutModifier: Modifier = Modifier,
    fromScene: SceneKey = TestScenes.SceneA,
    toScene: SceneKey = TestScenes.SceneB,
    builder: TransitionTestBuilder.() -> Unit,
) {
    testTransition(
        from = fromScene,
        to = toScene,
        transitionLayout = { currentScene, onChangeScene ->
            SceneTransitionLayout(
                currentScene,
                onChangeScene,
                transitions { from(fromScene, to = toScene, builder = transition) },
                layoutModifier,
            ) {
                scene(fromScene, content = fromSceneContent)
                scene(toScene, content = toSceneContent)
            }
        },
        builder,
    )
}

data class TransitionRecordingSpec(
    val recordBefore: Boolean = true,
    val recordAfter: Boolean = true,
    val timeSeriesCapture: TimeSeriesCaptureScope<SemanticsNodeInteractionsProvider>.() -> Unit
)

/** Captures the feature using [capture] on the [element]. */
fun TimeSeriesCaptureScope<SemanticsNodeInteractionsProvider>.featureOfElement(
    element: ElementKey,
    capture: FeatureCapture<SemanticsNode, *>,
    name: String = "${element.debugName}_${capture.name}"
) {
    feature(isElement(element), capture, name)
}

/** Records the transition between two scenes of [transitionLayout][SceneTransitionLayout]. */
fun MotionTestRule<ComposeToolkit>.recordTransition(
    fromSceneContent: @Composable SceneScope.() -> Unit,
    toSceneContent: @Composable SceneScope.() -> Unit,
    transition: TransitionBuilder.() -> Unit,
    recordingSpec: TransitionRecordingSpec,
    layoutModifier: Modifier = Modifier,
    fromScene: SceneKey = TestScenes.SceneA,
    toScene: SceneKey = TestScenes.SceneB,
): RecordedMotion {
    val state =
        toolkit.composeContentTestRule.runOnUiThread {
            MutableSceneTransitionLayoutState(
                fromScene,
                transitions { from(fromScene, to = toScene, builder = transition) }
            )
        }

    return recordMotion(
        content = { play ->
            LaunchedEffect(play) {
                if (play) {
                    state.setTargetScene(toScene, coroutineScope = this)
                }
            }

            SceneTransitionLayout(
                state,
                layoutModifier,
            ) {
                scene(fromScene, content = fromSceneContent)
                scene(toScene, content = toSceneContent)
            }
        },
        ComposeRecordingSpec(
            MotionControl(delayRecording = { awaitCondition { state.isTransitioning() } }) {
                awaitCondition { !state.isTransitioning() }
            },
            recordBefore = recordingSpec.recordBefore,
            recordAfter = recordingSpec.recordAfter,
            timeSeriesCapture = recordingSpec.timeSeriesCapture
        )
    )
}

/**
 * Test the transition between two scenes of [transitionLayout][SceneTransitionLayout] at different
 * points in time.
 */
fun ComposeContentTestRule.testTransition(
    from: SceneKey,
    to: SceneKey,
    transitionLayout:
        @Composable
        (
            currentScene: SceneKey,
            onChangeScene: (SceneKey) -> Unit,
        ) -> Unit,
    builder: TransitionTestBuilder.() -> Unit,
) {
    val test = transitionTest(builder)
    val assertionScope =
        object : TransitionTestAssertionScope {
            override fun onElement(
                element: ElementKey,
                scene: SceneKey?
            ): SemanticsNodeInteraction {
                return onNode(isElement(element, scene))
            }
        }

    var currentScene by mutableStateOf(from)
    setContent { transitionLayout(currentScene, { currentScene = it }) }

    // Wait for the UI to be idle then test the before state.
    waitForIdle()
    test.before(assertionScope)

    // Manually advance the clock to the start of the animation.
    mainClock.autoAdvance = false

    // Change the current scene.
    currentScene = to

    // Advance by a frame to trigger recomposition, which will start the transition (i.e. it will
    // change the transitionState to be a Transition) in a LaunchedEffect.
    mainClock.advanceTimeByFrame()

    // Advance by another frame so that the animator we started gets its initial value and clock
    // starting time. We are now at progress = 0f.
    mainClock.advanceTimeByFrame()
    waitForIdle()

    // Test the assertions at specific points in time.
    test.timestamps.forEach { tsAssertion ->
        if (tsAssertion.timestampDelta > 0L) {
            mainClock.advanceTimeBy(tsAssertion.timestampDelta)
            waitForIdle()
        }

        tsAssertion.assertion(assertionScope)
    }

    // Go to the end state and test it.
    mainClock.autoAdvance = true
    waitForIdle()
    test.after(assertionScope)
}

private fun transitionTest(builder: TransitionTestBuilder.() -> Unit): TransitionTest {
    // Collect the assertion lambdas in [TransitionTest]. Note that the ordering is forced by the
    // builder, e.g. `before {}` must be called before everything else, then `at {}` (in increasing
    // order of timestamp), then `after {}`. That way the test code is run with the same order as it
    // is written, to avoid confusion.

    val impl =
        object : TransitionTestBuilder {
                var before: (TransitionTestAssertionScope.() -> Unit)? = null
                var after: (TransitionTestAssertionScope.() -> Unit)? = null
                val timestamps = mutableListOf<TimestampAssertion>()

                private var currentTimestamp = 0L

                override fun before(builder: TransitionTestAssertionScope.() -> Unit) {
                    check(before == null) { "before {} must be called maximum once" }
                    check(after == null) { "before {} must be called before after {}" }
                    check(timestamps.isEmpty()) { "before {} must be called before at(...) {}" }

                    before = builder
                }

                override fun at(timestamp: Long, builder: TransitionTestAssertionScope.() -> Unit) {
                    check(after == null) { "at(...) {} must be called before after {}" }
                    check(timestamp >= currentTimestamp) {
                        "at(...) must be called with timestamps in increasing order"
                    }
                    check(timestamp % 16 == 0L) {
                        "timestamp must be a multiple of the frame time (16ms)"
                    }

                    val delta = timestamp - currentTimestamp
                    currentTimestamp = timestamp

                    timestamps.add(TimestampAssertion(delta, builder))
                }

                override fun after(builder: TransitionTestAssertionScope.() -> Unit) {
                    check(after == null) { "after {} must be called maximum once" }
                    after = builder
                }
            }
            .apply(builder)

    return TransitionTest(
        before = impl.before ?: {},
        timestamps = impl.timestamps,
        after = impl.after ?: {},
    )
}

private class TransitionTest(
    val before: TransitionTestAssertionScope.() -> Unit,
    val after: TransitionTestAssertionScope.() -> Unit,
    val timestamps: List<TimestampAssertion>,
)

private class TimestampAssertion(
    val timestampDelta: Long,
    val assertion: TransitionTestAssertionScope.() -> Unit,
)
