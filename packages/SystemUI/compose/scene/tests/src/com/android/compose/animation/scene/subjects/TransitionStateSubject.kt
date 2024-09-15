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

package com.android.compose.animation.scene.subjects

import com.android.compose.animation.scene.OverscrollSpec
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionState
import com.google.common.truth.Fact.simpleFact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/** Assert on a [TransitionState]. */
fun assertThat(state: TransitionState): TransitionStateSubject {
    return Truth.assertAbout(TransitionStateSubject.transitionStates()).that(state)
}

/** Assert on a [TransitionState.Transition]. */
fun assertThat(transitions: TransitionState.Transition): TransitionSubject {
    return Truth.assertAbout(TransitionSubject.transitions()).that(transitions)
}

class TransitionStateSubject
private constructor(
    metadata: FailureMetadata,
    private val actual: TransitionState,
) : Subject(metadata, actual) {
    fun hasCurrentScene(sceneKey: SceneKey) {
        check("currentScene").that(actual.currentScene).isEqualTo(sceneKey)
    }

    fun isIdle(): TransitionState.Idle {
        if (actual !is TransitionState.Idle) {
            failWithActual(simpleFact("expected to be TransitionState.Idle"))
        }

        return actual as TransitionState.Idle
    }

    fun isTransition(): TransitionState.Transition {
        if (actual !is TransitionState.Transition) {
            failWithActual(simpleFact("expected to be TransitionState.Transition"))
        }

        return actual as TransitionState.Transition
    }

    companion object {
        fun transitionStates() = Factory { metadata, actual: TransitionState ->
            TransitionStateSubject(metadata, actual)
        }
    }
}

class TransitionSubject
private constructor(
    metadata: FailureMetadata,
    private val actual: TransitionState.Transition,
) : Subject(metadata, actual) {
    fun hasCurrentScene(sceneKey: SceneKey) {
        check("currentScene").that(actual.currentScene).isEqualTo(sceneKey)
    }

    fun hasFromScene(sceneKey: SceneKey) {
        check("fromScene").that(actual.fromScene).isEqualTo(sceneKey)
    }

    fun hasToScene(sceneKey: SceneKey) {
        check("toScene").that(actual.toScene).isEqualTo(sceneKey)
    }

    fun hasProgress(progress: Float, tolerance: Float = 0f) {
        check("progress").that(actual.progress).isWithin(tolerance).of(progress)
    }

    fun hasProgressVelocity(progressVelocity: Float, tolerance: Float = 0f) {
        check("progressVelocity")
            .that(actual.progressVelocity)
            .isWithin(tolerance)
            .of(progressVelocity)
    }

    fun isInitiatedByUserInput() {
        check("isInitiatedByUserInput").that(actual.isInitiatedByUserInput).isTrue()
    }

    fun hasIsUserInputOngoing(isUserInputOngoing: Boolean) {
        check("isUserInputOngoing").that(actual.isUserInputOngoing).isEqualTo(isUserInputOngoing)
    }

    fun hasOverscrollSpec(): OverscrollSpec {
        check("currentOverscrollSpec").that(actual.currentOverscrollSpec).isNotNull()
        return actual.currentOverscrollSpec!!
    }

    fun hasNoOverscrollSpec() {
        check("currentOverscrollSpec").that(actual.currentOverscrollSpec).isNull()
    }

    fun hasBouncingScene(scene: SceneKey) {
        if (actual !is TransitionState.HasOverscrollProperties) {
            failWithActual(simpleFact("expected to be TransitionState.HasOverscrollProperties"))
        }

        check("bouncingScene")
            .that((actual as TransitionState.HasOverscrollProperties).bouncingScene)
            .isEqualTo(scene)
    }

    companion object {
        fun transitions() = Factory { metadata, actual: TransitionState.Transition ->
            TransitionSubject(metadata, actual)
        }
    }
}
