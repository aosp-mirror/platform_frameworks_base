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

package com.android.compose.test

import androidx.compose.foundation.gestures.Orientation
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.Transition
import kotlinx.coroutines.CompletableDeferred

/** A [Transition.ShowOrHideOverlay] for tests that will be finished once [finish] is called. */
abstract class TestOverlayTransition(
    fromScene: SceneKey,
    overlay: OverlayKey,
    replacedTransition: Transition?,
) :
    Transition.ShowOrHideOverlay(
        overlay = overlay,
        fromOrToScene = fromScene,
        fromContent = fromScene,
        toContent = overlay,
        replacedTransition = replacedTransition,
    ) {
    private val finishCompletable = CompletableDeferred<Unit>()

    override suspend fun run() {
        finishCompletable.await()
    }

    /** Finish this transition. */
    fun finish() {
        finishCompletable.complete(Unit)
    }
}

/** A utility to easily create a [TestOverlayTransition] in tests. */
fun transition(
    fromScene: SceneKey,
    overlay: OverlayKey,
    isEffectivelyShown: () -> Boolean = { true },
    progress: () -> Float = { 0f },
    progressVelocity: () -> Float = { 0f },
    previewProgress: () -> Float = { 0f },
    previewProgressVelocity: () -> Float = { 0f },
    isInPreviewStage: () -> Boolean = { false },
    interruptionProgress: () -> Float = { 0f },
    isInitiatedByUserInput: Boolean = false,
    isUserInputOngoing: Boolean = false,
    isUpOrLeft: Boolean = false,
    bouncingContent: ContentKey? = null,
    orientation: Orientation = Orientation.Horizontal,
    onFreezeAndAnimate: ((TestOverlayTransition) -> Unit)? = null,
    replacedTransition: Transition? = null,
): TestOverlayTransition {
    return object :
        TestOverlayTransition(fromScene, overlay, replacedTransition),
        TransitionState.HasOverscrollProperties {
        override val isEffectivelyShown: Boolean
            get() = isEffectivelyShown()

        override val progress: Float
            get() = progress()

        override val progressVelocity: Float
            get() = progressVelocity()

        override val previewProgress: Float
            get() = previewProgress()

        override val previewProgressVelocity: Float
            get() = previewProgressVelocity()

        override val isInPreviewStage: Boolean
            get() = isInPreviewStage()

        override val isInitiatedByUserInput: Boolean = isInitiatedByUserInput
        override val isUserInputOngoing: Boolean = isUserInputOngoing
        override val isUpOrLeft: Boolean = isUpOrLeft
        override val bouncingContent: ContentKey? = bouncingContent
        override val orientation: Orientation = orientation
        override val absoluteDistance = 0f

        override fun freezeAndAnimateToCurrentState() {
            if (onFreezeAndAnimate != null) {
                onFreezeAndAnimate(this)
            } else {
                finish()
            }
        }

        override fun interruptionProgress(layoutImpl: SceneTransitionLayoutImpl): Float {
            return interruptionProgress()
        }
    }
}
