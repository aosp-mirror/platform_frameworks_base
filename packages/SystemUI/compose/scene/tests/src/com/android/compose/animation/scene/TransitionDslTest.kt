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

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transformation.CustomPropertyTransformation
import com.android.compose.animation.scene.transformation.OverscrollTranslate
import com.android.compose.animation.scene.transformation.PropertyTransformation
import com.android.compose.animation.scene.transformation.PropertyTransformationScope
import com.android.compose.animation.scene.transformation.TransformationMatcher
import com.android.compose.animation.scene.transformation.TransformationRange
import com.android.compose.test.transition
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransitionDslTest {
    @Test
    fun emptyTransitions() {
        val transitions = transitions {}
        assertThat(transitions.transitionSpecs).isEmpty()
    }

    @Test
    fun manyTransitions() {
        val transitions = transitions {
            from(SceneA, to = SceneB)
            from(SceneB, to = SceneC)
            from(SceneC, to = SceneA)
        }
        assertThat(transitions.transitionSpecs).hasSize(3)
    }

    @Test
    fun toFromBuilders() {
        val transitions = transitions {
            from(SceneA, to = SceneB)
            from(SceneB)
            to(SceneC)
        }

        assertThat(transitions.transitionSpecs)
            .comparingElementsUsing(
                Correspondence.transforming<TransitionSpecImpl, Pair<ContentKey?, ContentKey?>>(
                    { it?.from to it?.to },
                    "has (from, to) equal to",
                )
            )
            .containsExactly(SceneA to SceneB, SceneB to null, null to SceneC)
    }

    private fun aToB() = transition(SceneA, SceneB)

    @Test
    fun defaultTransitionSpec() {
        val transitions = transitions { from(SceneA, to = SceneB) }
        val transformationSpec = transitions.transitionSpecs.single().transformationSpec(aToB())
        assertThat(transformationSpec.progressSpec).isInstanceOf(SpringSpec::class.java)
    }

    @Test
    fun customTransitionSpec() {
        val transitions = transitions {
            from(SceneA, to = SceneB) { spec = tween(durationMillis = 42) }
        }
        val transformationSpec = transitions.transitionSpecs.single().transformationSpec(aToB())
        assertThat(transformationSpec.progressSpec).isInstanceOf(TweenSpec::class.java)
        assertThat((transformationSpec.progressSpec as TweenSpec).durationMillis).isEqualTo(42)
    }

    @Test
    fun defaultRange() {
        val transitions = transitions { from(SceneA, to = SceneB) { fade(TestElements.Foo) } }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec(aToB()).transformationMatchers
        assertThat(transformations.size).isEqualTo(1)
        assertThat(transformations.single().range).isEqualTo(null)
    }

    @Test
    fun fractionRange() {
        val transitions = transitions {
            from(SceneA, to = SceneB) {
                fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                fractionRange(start = 0.2f) { fade(TestElements.Foo) }
                fractionRange(end = 0.9f) { fade(TestElements.Foo) }
                fractionRange(
                    start = 0.1f,
                    end = 0.8f,
                    easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f),
                ) {
                    fade(TestElements.Foo)
                }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec(aToB()).transformationMatchers
        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 0.1f, end = 0.8f),
                TransformationRange(start = 0.2f, end = TransformationRange.BoundUnspecified),
                TransformationRange(start = TransformationRange.BoundUnspecified, end = 0.9f),
                TransformationRange(start = 0.1f, end = 0.8f, CubicBezierEasing(0.1f, 0.1f, 0f, 1f)),
            )
    }

    @Test
    fun timestampRange() {
        val transitions = transitions {
            from(SceneA, to = SceneB) {
                spec = tween(500)

                timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
                timestampRange(startMillis = 200) { fade(TestElements.Foo) }
                timestampRange(endMillis = 400) { fade(TestElements.Foo) }
                timestampRange(
                    startMillis = 100,
                    endMillis = 300,
                    easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f),
                ) {
                    fade(TestElements.Foo)
                }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec(aToB()).transformationMatchers
        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 100 / 500f, end = 300 / 500f),
                TransformationRange(start = 200 / 500f, end = TransformationRange.BoundUnspecified),
                TransformationRange(start = TransformationRange.BoundUnspecified, end = 400 / 500f),
                TransformationRange(
                    start = 100 / 500f,
                    end = 300 / 500f,
                    easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f),
                ),
            )
    }

    @Test
    fun reversed() {
        val transitions = transitions {
            from(SceneA, to = SceneB) {
                spec = tween(500)
                reversed {
                    fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                    timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
                }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec(aToB()).transformationMatchers
        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 1f - 0.8f, end = 1f - 0.1f),
                TransformationRange(start = 1f - 300 / 500f, end = 1f - 100 / 500f),
            )
    }

    @Test
    fun defaultReversed() {
        val transitions = transitions {
            from(
                SceneA,
                to = SceneB,
                preview = { fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) } },
                reversePreview = {
                    fractionRange(start = 0.5f, end = 0.6f) { fade(TestElements.Foo) }
                },
            ) {
                spec = tween(500)
                fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
            }
        }

        // Fetch the transition from B to A, which will automatically reverse the transition from A
        // to B we defined.
        val transitionSpec = transitions.transitionSpec(from = SceneB, to = SceneA, key = null)

        val transformations = transitionSpec.transformationSpec(aToB()).transformationMatchers

        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 1f - 0.8f, end = 1f - 0.1f),
                TransformationRange(start = 1f - 300 / 500f, end = 1f - 100 / 500f),
            )

        val previewTransformations =
            transitionSpec.previewTransformationSpec(aToB())?.transformationMatchers

        assertThat(previewTransformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(TransformationRange(start = 0.5f, end = 0.6f))
    }

    @Test
    fun defaultPredictiveBack() {
        val transitions = transitions {
            from(
                SceneA,
                to = SceneB,
                preview = { fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) } },
            ) {
                spec = tween(500)
                fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
            }
        }

        // Verify that fetching the transitionSpec with the PredictiveBack key defaults to the above
        // transition despite it not having the PredictiveBack key set.
        val transitionSpec =
            transitions.transitionSpec(
                from = SceneA,
                to = SceneB,
                key = TransitionKey.PredictiveBack,
            )

        val transformations = transitionSpec.transformationSpec(aToB()).transformationMatchers

        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 0.1f, end = 0.8f),
                TransformationRange(start = 100 / 500f, end = 300 / 500f),
            )

        val previewTransformations =
            transitionSpec.previewTransformationSpec(aToB())?.transformationMatchers

        assertThat(previewTransformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(TransformationRange(start = 0.1f, end = 0.8f))
    }

    @Test
    fun springSpec() {
        val defaultSpec = spring<Float>(stiffness = 1f)
        val specFromAToC = spring<Float>(stiffness = 2f)
        val transitions = transitions {
            defaultSwipeSpec = defaultSpec

            from(SceneA, to = SceneB) {
                // Default swipe spec.
            }
            from(SceneA, to = SceneC) { swipeSpec = specFromAToC }
        }

        assertThat(transitions.defaultSwipeSpec).isSameInstanceAs(defaultSpec)

        // A => B does not have a custom spec.
        assertThat(
                transitions
                    .transitionSpec(from = SceneA, to = SceneB, key = null)
                    .transformationSpec(aToB())
                    .swipeSpec
            )
            .isNull()

        // A => C has a custom swipe spec.
        assertThat(
                transitions
                    .transitionSpec(from = SceneA, to = SceneC, key = null)
                    .transformationSpec(transition(from = SceneA, to = SceneC))
                    .swipeSpec
            )
            .isSameInstanceAs(specFromAToC)
    }

    @Test
    fun overscrollSpec() {
        val transitions = transitions {
            overscroll(SceneA, Orientation.Vertical) {
                translate(TestElements.Bar, x = { 1f }, y = { 2f })
            }
        }

        val overscrollSpec = transitions.overscrollSpecs.single()
        val transformation =
            overscrollSpec.transformationSpec.transformationMatchers.single().factory.create()
        assertThat(transformation).isInstanceOf(OverscrollTranslate::class.java)
    }

    @Test
    fun overscrollSpec_for_overscrollDisabled() {
        val transitions = transitions { overscrollDisabled(SceneA, Orientation.Vertical) }
        val overscrollSpec = transitions.overscrollSpecs.single()
        assertThat(overscrollSpec.transformationSpec.transformationMatchers).isEmpty()
    }

    @Test
    fun overscrollSpec_throwIfTransformationsIsEmpty() {
        assertThrows(IllegalStateException::class.java) {
            transitions { overscroll(SceneA, Orientation.Vertical) {} }
        }
    }

    @Test
    fun transitionIsPassedToBuilder() = runTest {
        var transitionPassedToBuilder: TransitionState.Transition? = null
        val state =
            MutableSceneTransitionLayoutState(
                SceneA,
                transitions { from(SceneA, to = SceneB) { transitionPassedToBuilder = transition } },
            )

        val transition = aToB()
        state.startTransitionImmediately(animationScope = backgroundScope, transition)
        assertThat(transitionPassedToBuilder).isSameInstanceAs(transition)
    }

    @Test
    fun customTransitionsAreNotSupportedInRanges() = runTest {
        val transitions = transitions {
            from(SceneA, to = SceneB) {
                fractionRange {
                    transformation(TestElements.Foo) {
                        object : CustomPropertyTransformation<IntSize> {
                            override val property = PropertyTransformation.Property.Size

                            override fun PropertyTransformationScope.transform(
                                content: ContentKey,
                                element: ElementKey,
                                transition: TransitionState.Transition,
                                transitionScope: CoroutineScope,
                            ): IntSize = IntSize.Zero
                        }
                    }
                }
            }
        }

        val state = MutableSceneTransitionLayoutState(SceneA, transitions)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { state.startTransition(transition(from = SceneA, to = SceneB)) }
        }
    }

    companion object {
        private val TRANSFORMATION_RANGE =
            Correspondence.transforming<TransformationMatcher, TransformationRange?>(
                { it?.range },
                "has range equal to",
            )
    }
}
