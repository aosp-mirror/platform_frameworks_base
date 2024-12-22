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

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.OverscrollSpec
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.google.common.truth.Fact.simpleFact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/** Assert on a [TransitionState]. */
fun assertThat(state: TransitionState): TransitionStateSubject {
    return Truth.assertAbout(TransitionStateSubject.transitionStates()).that(state)
}

/** Assert on a [TransitionState.Transition.ChangeScene]. */
fun assertThat(transition: TransitionState.Transition.ChangeScene): SceneTransitionSubject {
    return Truth.assertAbout(SceneTransitionSubject.sceneTransitions()).that(transition)
}

/** Assert on a [TransitionState.Transition.ShowOrHideOverlay]. */
fun assertThat(
    transition: TransitionState.Transition.ShowOrHideOverlay
): ShowOrHideOverlayTransitionSubject {
    return Truth.assertAbout(ShowOrHideOverlayTransitionSubject.showOrHideOverlayTransitions())
        .that(transition)
}

/** Assert on a [TransitionState.Transition.ReplaceOverlay]. */
fun assertThat(
    transition: TransitionState.Transition.ReplaceOverlay
): ReplaceOverlayTransitionSubject {
    return Truth.assertAbout(ReplaceOverlayTransitionSubject.replaceOverlayTransitions())
        .that(transition)
}

class TransitionStateSubject
private constructor(metadata: FailureMetadata, private val actual: TransitionState) :
    Subject(metadata, actual) {
    fun hasCurrentScene(sceneKey: SceneKey) {
        check("currentScene").that(actual.currentScene).isEqualTo(sceneKey)
    }

    fun hasCurrentOverlays(vararg overlays: OverlayKey) {
        check("currentOverlays").that(actual.currentOverlays).containsExactlyElementsIn(overlays)
    }

    fun isIdle(): TransitionState.Idle {
        if (actual !is TransitionState.Idle) {
            failWithActual(simpleFact("expected to be TransitionState.Idle"))
        }

        return actual as TransitionState.Idle
    }

    fun isSceneTransition(): TransitionState.Transition.ChangeScene {
        if (actual !is TransitionState.Transition.ChangeScene) {
            failWithActual(
                simpleFact("expected to be TransitionState.Transition.ChangeCurrentScene")
            )
        }

        return actual as TransitionState.Transition.ChangeScene
    }

    fun isShowOrHideOverlayTransition(): TransitionState.Transition.ShowOrHideOverlay {
        if (actual !is TransitionState.Transition.ShowOrHideOverlay) {
            failWithActual(
                simpleFact("expected to be TransitionState.Transition.ShowOrHideOverlay")
            )
        }

        return actual as TransitionState.Transition.ShowOrHideOverlay
    }

    fun isReplaceOverlayTransition(): TransitionState.Transition.ReplaceOverlay {
        if (actual !is TransitionState.Transition.ReplaceOverlay) {
            failWithActual(simpleFact("expected to be TransitionState.Transition.ReplaceOverlay"))
        }

        return actual as TransitionState.Transition.ReplaceOverlay
    }

    companion object {
        fun transitionStates() = Factory { metadata, actual: TransitionState ->
            TransitionStateSubject(metadata, actual)
        }
    }
}

abstract class BaseTransitionSubject<T : TransitionState.Transition>(
    metadata: FailureMetadata,
    protected val actual: T,
) : Subject(metadata, actual) {
    fun hasCurrentScene(sceneKey: SceneKey) {
        check("currentScene").that(actual.currentScene).isEqualTo(sceneKey)
    }

    fun hasCurrentOverlays(vararg overlays: OverlayKey) {
        check("currentOverlays").that(actual.currentOverlays).containsExactlyElementsIn(overlays)
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

    fun hasPreviewProgress(progress: Float, tolerance: Float = 0f) {
        check("previewProgress").that(actual.previewProgress).isWithin(tolerance).of(progress)
    }

    fun hasPreviewProgressVelocity(progressVelocity: Float, tolerance: Float = 0f) {
        check("previewProgressVelocity")
            .that(actual.previewProgressVelocity)
            .isWithin(tolerance)
            .of(progressVelocity)
    }

    fun isInPreviewStage() {
        check("isInPreviewStage").that(actual.isInPreviewStage).isTrue()
    }

    fun isNotInPreviewStage() {
        check("isInPreviewStage").that(actual.isInPreviewStage).isFalse()
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

    fun hasBouncingContent(content: ContentKey) {
        val actual = actual
        if (actual !is TransitionState.HasOverscrollProperties) {
            failWithActual(simpleFact("expected to be ContentState.HasOverscrollProperties"))
        }

        check("bouncingContent")
            .that((actual as TransitionState.HasOverscrollProperties).bouncingContent)
            .isEqualTo(content)
    }
}

class SceneTransitionSubject
private constructor(metadata: FailureMetadata, actual: TransitionState.Transition.ChangeScene) :
    BaseTransitionSubject<TransitionState.Transition.ChangeScene>(metadata, actual) {
    fun hasFromScene(sceneKey: SceneKey) {
        check("fromScene").that(actual.fromScene).isEqualTo(sceneKey)
    }

    fun hasToScene(sceneKey: SceneKey) {
        check("toScene").that(actual.toScene).isEqualTo(sceneKey)
    }

    companion object {
        fun sceneTransitions() =
            Factory { metadata, actual: TransitionState.Transition.ChangeScene ->
                SceneTransitionSubject(metadata, actual)
            }
    }
}

class ShowOrHideOverlayTransitionSubject
private constructor(
    metadata: FailureMetadata,
    actual: TransitionState.Transition.ShowOrHideOverlay,
) : BaseTransitionSubject<TransitionState.Transition.ShowOrHideOverlay>(metadata, actual) {
    fun hasFromOrToScene(fromOrToScene: SceneKey) {
        check("fromOrToScene").that(actual.fromOrToScene).isEqualTo(fromOrToScene)
    }

    fun hasOverlay(overlay: OverlayKey) {
        check("overlay").that(actual.overlay).isEqualTo(overlay)
    }

    companion object {
        fun showOrHideOverlayTransitions() =
            Factory { metadata, actual: TransitionState.Transition.ShowOrHideOverlay ->
                ShowOrHideOverlayTransitionSubject(metadata, actual)
            }
    }
}

class ReplaceOverlayTransitionSubject
private constructor(metadata: FailureMetadata, actual: TransitionState.Transition.ReplaceOverlay) :
    BaseTransitionSubject<TransitionState.Transition.ReplaceOverlay>(metadata, actual) {
    fun hasFromOverlay(fromOverlay: OverlayKey) {
        check("fromOverlay").that(actual.fromOverlay).isEqualTo(fromOverlay)
    }

    fun hasToOverlay(toOverlay: OverlayKey) {
        check("toOverlay").that(actual.toOverlay).isEqualTo(toOverlay)
    }

    companion object {
        fun replaceOverlayTransitions() =
            Factory { metadata, actual: TransitionState.Transition.ReplaceOverlay ->
                ReplaceOverlayTransitionSubject(metadata, actual)
            }
    }
}
