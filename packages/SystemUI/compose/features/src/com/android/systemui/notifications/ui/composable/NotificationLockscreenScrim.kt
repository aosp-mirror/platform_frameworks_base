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

package com.android.systemui.notifications.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationLockscreenScrimViewModel
import kotlinx.coroutines.launch

/**
 * A full-screen notifications scrim that is only visible after transitioning from Shade scene to
 * Lockscreen Scene and ending user input, at which point it fades out, visually completing the
 * transition.
 */
@Composable
fun SceneScope.NotificationLockscreenScrim(
    viewModel: NotificationLockscreenScrimViewModel,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val shadeMode = viewModel.shadeMode.collectAsStateWithLifecycle()

    // Important: Make sure that shouldShowScrimFadeOut() is checked the first time the Lockscreen
    // scene is composed.
    val useFadeOutOnComposition =
        remember(shadeMode.value) {
            layoutState.currentTransition?.let { currentTransition ->
                shouldShowScrimFadeOut(currentTransition, shadeMode.value)
            } ?: false
        }

    val alphaAnimatable = remember { Animatable(1f) }

    LaunchedEffect(
        alphaAnimatable,
        layoutState.currentTransition,
        useFadeOutOnComposition,
        shadeMode,
    ) {
        val currentTransition = layoutState.currentTransition
        if (
            useFadeOutOnComposition &&
                currentTransition != null &&
                shouldShowScrimFadeOut(currentTransition, shadeMode.value) &&
                currentTransition.isUserInputOngoing
        ) {
            // keep scrim visible until user lifts their finger.
            viewModel.setAlphaForLockscreenFadeIn(0f)
            alphaAnimatable.snapTo(1f)
        } else if (
            useFadeOutOnComposition &&
                (currentTransition == null ||
                    (shouldShowScrimFadeOut(currentTransition, shadeMode.value) &&
                        !currentTransition.isUserInputOngoing))
        ) {
            // we no longer want to keep the scrim from fading out, so animate the scrim fade-out
            // and pipe the progress to the view model as well, so NSSL can fade-in the stack in
            // tandem.
            viewModel.setAlphaForLockscreenFadeIn(0f)
            coroutineScope.launch {
                snapshotFlow { alphaAnimatable.value }
                    .collect { viewModel.setAlphaForLockscreenFadeIn(1 - it) }
            }
            alphaAnimatable.animateTo(0f, tween())
        } else {
            // disable the scrim fade logic.
            viewModel.setAlphaForLockscreenFadeIn(1f)
            alphaAnimatable.snapTo(0f)
        }
    }

    val isBouncerToLockscreen =
        layoutState.currentTransition?.isTransitioning(
            from = Scenes.Bouncer,
            to = Scenes.Lockscreen,
        ) ?: false

    Box(
        modifier
            .fillMaxSize()
            .element(viewModel.element.key)
            .graphicsLayer { alpha = alphaAnimatable.value }
            .background(viewModel.element.color(isBouncerToLockscreen))
    )
}

private fun shouldShowScrimFadeOut(
    currentTransition: TransitionState.Transition,
    shadeMode: ShadeMode,
): Boolean {
    return shadeMode != ShadeMode.Dual &&
        currentTransition.isInitiatedByUserInput &&
        (currentTransition.isTransitioning(from = Scenes.Shade, to = Scenes.Lockscreen) ||
            currentTransition.isTransitioning(from = Scenes.Bouncer, to = Scenes.Lockscreen))
}
