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

package com.android.compose.animation.scene

import com.android.compose.animation.scene.content.state.TransitionState
import kotlinx.coroutines.CoroutineScope

/** Trigger a one-off transition to show or hide an overlay. */
internal fun CoroutineScope.showOrHideOverlay(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    overlay: OverlayKey,
    fromOrToScene: SceneKey,
    isShowing: Boolean,
    transitionKey: TransitionKey?,
    replacedTransition: TransitionState.Transition.ShowOrHideOverlay?,
    reversed: Boolean,
): TransitionState.Transition.ShowOrHideOverlay {
    val targetProgress = if (reversed) 0f else 1f
    val (fromContent, toContent) =
        if (isShowing xor reversed) {
            fromOrToScene to overlay
        } else {
            overlay to fromOrToScene
        }

    val oneOffAnimation = OneOffAnimation()
    val transition =
        OneOffShowOrHideOverlayTransition(
            overlay = overlay,
            fromOrToScene = fromOrToScene,
            fromContent = fromContent,
            toContent = toContent,
            isEffectivelyShown = isShowing,
            key = transitionKey,
            replacedTransition = replacedTransition,
            oneOffAnimation = oneOffAnimation,
        )

    animateContent(
        layoutState = layoutState,
        transition = transition,
        oneOffAnimation = oneOffAnimation,
        targetProgress = targetProgress,
    )

    return transition
}

/** Trigger a one-off transition to replace an overlay by another one. */
internal fun CoroutineScope.replaceOverlay(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    fromOverlay: OverlayKey,
    toOverlay: OverlayKey,
    transitionKey: TransitionKey?,
    replacedTransition: TransitionState.Transition.ReplaceOverlay?,
    reversed: Boolean,
): TransitionState.Transition.ReplaceOverlay {
    val targetProgress = if (reversed) 0f else 1f
    val effectivelyShownOverlay = if (reversed) fromOverlay else toOverlay

    val oneOffAnimation = OneOffAnimation()
    val transition =
        OneOffOverlayReplacingTransition(
            fromOverlay = fromOverlay,
            toOverlay = toOverlay,
            effectivelyShownOverlay = effectivelyShownOverlay,
            key = transitionKey,
            replacedTransition = replacedTransition,
            oneOffAnimation = oneOffAnimation,
        )

    animateContent(
        layoutState = layoutState,
        transition = transition,
        oneOffAnimation = oneOffAnimation,
        targetProgress = targetProgress,
    )

    return transition
}

private class OneOffShowOrHideOverlayTransition(
    overlay: OverlayKey,
    fromOrToScene: SceneKey,
    fromContent: ContentKey,
    toContent: ContentKey,
    override val isEffectivelyShown: Boolean,
    override val key: TransitionKey?,
    replacedTransition: TransitionState.Transition?,
    private val oneOffAnimation: OneOffAnimation,
) :
    TransitionState.Transition.ShowOrHideOverlay(
        overlay,
        fromOrToScene,
        fromContent,
        toContent,
        replacedTransition,
    ) {
    override val progress: Float
        get() = oneOffAnimation.progress

    override val progressVelocity: Float
        get() = oneOffAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = false
    override val isUserInputOngoing: Boolean = false

    override suspend fun run() {
        oneOffAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        oneOffAnimation.freezeAndAnimateToCurrentState()
    }
}

private class OneOffOverlayReplacingTransition(
    fromOverlay: OverlayKey,
    toOverlay: OverlayKey,
    override val effectivelyShownOverlay: OverlayKey,
    override val key: TransitionKey?,
    replacedTransition: TransitionState.Transition?,
    private val oneOffAnimation: OneOffAnimation,
) : TransitionState.Transition.ReplaceOverlay(fromOverlay, toOverlay, replacedTransition) {
    override val progress: Float
        get() = oneOffAnimation.progress

    override val progressVelocity: Float
        get() = oneOffAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = false
    override val isUserInputOngoing: Boolean = false

    override suspend fun run() {
        oneOffAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        oneOffAnimation.freezeAndAnimateToCurrentState()
    }
}
