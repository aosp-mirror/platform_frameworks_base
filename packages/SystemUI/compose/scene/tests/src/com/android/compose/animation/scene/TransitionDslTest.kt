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

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.transformation.OverscrollTranslate
import com.android.compose.animation.scene.transformation.Transformation
import com.android.compose.animation.scene.transformation.TransformationRange
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
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
            from(TestScenes.SceneA, to = TestScenes.SceneB)
            from(TestScenes.SceneB, to = TestScenes.SceneC)
            from(TestScenes.SceneC, to = TestScenes.SceneA)
        }
        assertThat(transitions.transitionSpecs).hasSize(3)
    }

    @Test
    fun toFromBuilders() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB)
            from(TestScenes.SceneB)
            to(TestScenes.SceneC)
        }

        assertThat(transitions.transitionSpecs)
            .comparingElementsUsing(
                Correspondence.transforming<TransitionSpecImpl, Pair<SceneKey?, SceneKey?>>(
                    { it?.from to it?.to },
                    "has (from, to) equal to"
                )
            )
            .containsExactly(
                TestScenes.SceneA to TestScenes.SceneB,
                TestScenes.SceneB to null,
                null to TestScenes.SceneC,
            )
    }

    @Test
    fun defaultTransitionSpec() {
        val transitions = transitions { from(TestScenes.SceneA, to = TestScenes.SceneB) }
        val transformationSpec = transitions.transitionSpecs.single().transformationSpec()
        assertThat(transformationSpec.progressSpec).isInstanceOf(SpringSpec::class.java)
    }

    @Test
    fun customTransitionSpec() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB) { spec = tween(durationMillis = 42) }
        }
        val transformationSpec = transitions.transitionSpecs.single().transformationSpec()
        assertThat(transformationSpec.progressSpec).isInstanceOf(TweenSpec::class.java)
        assertThat((transformationSpec.progressSpec as TweenSpec).durationMillis).isEqualTo(42)
    }

    @Test
    fun defaultRange() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB) { fade(TestElements.Foo) }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec().transformations
        assertThat(transformations.size).isEqualTo(1)
        assertThat(transformations.single().range).isEqualTo(null)
    }

    @Test
    fun fractionRange() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB) {
                fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                fractionRange(start = 0.2f) { fade(TestElements.Foo) }
                fractionRange(end = 0.9f) { fade(TestElements.Foo) }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec().transformations
        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 0.1f, end = 0.8f),
                TransformationRange(start = 0.2f, end = TransformationRange.BoundUnspecified),
                TransformationRange(start = TransformationRange.BoundUnspecified, end = 0.9f),
            )
    }

    @Test
    fun timestampRange() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB) {
                spec = tween(500)

                timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
                timestampRange(startMillis = 200) { fade(TestElements.Foo) }
                timestampRange(endMillis = 400) { fade(TestElements.Foo) }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec().transformations
        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 100 / 500f, end = 300 / 500f),
                TransformationRange(start = 200 / 500f, end = TransformationRange.BoundUnspecified),
                TransformationRange(start = TransformationRange.BoundUnspecified, end = 400 / 500f),
            )
    }

    @Test
    fun reversed() {
        val transitions = transitions {
            from(TestScenes.SceneA, to = TestScenes.SceneB) {
                spec = tween(500)
                reversed {
                    fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                    timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
                }
            }
        }

        val transformations =
            transitions.transitionSpecs.single().transformationSpec().transformations
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
            from(TestScenes.SceneA, to = TestScenes.SceneB) {
                spec = tween(500)
                fractionRange(start = 0.1f, end = 0.8f) { fade(TestElements.Foo) }
                timestampRange(startMillis = 100, endMillis = 300) { fade(TestElements.Foo) }
            }
        }

        // Fetch the transition from B to A, which will automatically reverse the transition from A
        // to B we defined.
        val transformations =
            transitions
                .transitionSpec(from = TestScenes.SceneB, to = TestScenes.SceneA, key = null)
                .transformationSpec()
                .transformations

        assertThat(transformations)
            .comparingElementsUsing(TRANSFORMATION_RANGE)
            .containsExactly(
                TransformationRange(start = 1f - 0.8f, end = 1f - 0.1f),
                TransformationRange(start = 1f - 300 / 500f, end = 1f - 100 / 500f),
            )
    }

    @Test
    fun springSpec() {
        val defaultSpec = spring<Float>(stiffness = 1f)
        val specFromAToC = spring<Float>(stiffness = 2f)
        val transitions = transitions {
            defaultSwipeSpec = defaultSpec

            from(TestScenes.SceneA, to = TestScenes.SceneB) {
                // Default swipe spec.
            }
            from(TestScenes.SceneA, to = TestScenes.SceneC) { swipeSpec = specFromAToC }
        }

        assertThat(transitions.defaultSwipeSpec).isSameInstanceAs(defaultSpec)

        // A => B does not have a custom spec.
        assertThat(
                transitions
                    .transitionSpec(from = TestScenes.SceneA, to = TestScenes.SceneB, key = null)
                    .transformationSpec()
                    .swipeSpec
            )
            .isNull()

        // A => C has a custom swipe spec.
        assertThat(
                transitions
                    .transitionSpec(from = TestScenes.SceneA, to = TestScenes.SceneC, key = null)
                    .transformationSpec()
                    .swipeSpec
            )
            .isSameInstanceAs(specFromAToC)
    }

    @Test
    fun overscrollSpec() {
        val transitions = transitions {
            overscroll(TestScenes.SceneA, Orientation.Vertical) {
                translate(TestElements.Bar, x = { 1f }, y = { 2f })
            }
        }

        val overscrollSpec = transitions.overscrollSpecs.single()
        val transformation = overscrollSpec.transformationSpec.transformations.single()
        assertThat(transformation).isInstanceOf(OverscrollTranslate::class.java)
    }

    companion object {
        private val TRANSFORMATION_RANGE =
            Correspondence.transforming<Transformation, TransformationRange?>(
                { it?.range },
                "has range equal to"
            )
    }
}
