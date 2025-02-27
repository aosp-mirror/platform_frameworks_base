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

package com.android.systemui.inputdevice.tutorial.ui.composable

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.Ref
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgress
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NotStarted
import com.android.systemui.res.R

@Composable
fun TutorialAnimation(
    actionState: TutorialActionState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = actionState::class,
            transitionSpec = {
                EnterTransition.None.togetherWith(
                        fadeOut(animationSpec = tween(durationMillis = 10, easing = LinearEasing))
                    )
                    // we don't want size transform because when targetState animation is loaded for
                    // the first time, AnimatedContent thinks target size is smaller and tries to
                    // shrink initial state
                    .using(sizeTransform = null)
            },
        ) { state ->
            when (state) {
                NotStarted::class ->
                    EducationAnimation(
                        config.animations.educationResId,
                        config.colors.animationColors,
                    )
                InProgress::class ->
                    InProgressAnimation(
                        // actionState can be already of different class while this composable is
                        // transitioning to another one
                        actionState as? InProgress,
                        config.animations.educationResId,
                        config.colors.animationColors,
                    )
                Finished::class ->
                    // Below cast is safe as Finished state is the last state and afterwards we can
                    // only leave the screen so this composable would be no longer displayed
                    SuccessAnimation(actionState as Finished, config.colors.animationColors)
            }
        }
    }
}

@Composable
private fun EducationAnimation(
    @RawRes educationAnimationId: Int,
    animationProperties: LottieDynamicProperties,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(educationAnimationId))
    var isPlaying by remember { mutableStateOf(true) }
    val progress by
        animateLottieCompositionAsState(
            composition,
            iterations = LottieConstants.IterateForever,
            isPlaying = isPlaying,
            restartOnPlay = false,
        )
    val animationDescription = stringResource(R.string.tutorial_animation_content_description)
    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = animationProperties,
        modifier =
            Modifier.fillMaxSize()
                .clickable { isPlaying = !isPlaying }
                .semantics { contentDescription = animationDescription },
    )
}

@Composable
private fun SuccessAnimation(
    finishedState: Finished,
    animationProperties: LottieDynamicProperties,
) {
    val composition by
        rememberLottieComposition(LottieCompositionSpec.RawRes(finishedState.successAnimation))
    val progress by animateLottieCompositionAsState(composition, iterations = 1)
    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = animationProperties,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun InProgressAnimation(
    state: InProgress?,
    @RawRes inProgressAnimationId: Int,
    animationProperties: LottieDynamicProperties,
) {
    // Caching latest progress for when we're animating this view away and state is null.
    // Without this there's jumpcut in the animation while it's animating away.
    // state should never be null when composable appears, only when disappearing
    val cached = remember { Ref<InProgress>() }
    cached.value = state ?: cached.value
    val progress = cached.value?.progress ?: 0f

    val composition by
        rememberLottieComposition(LottieCompositionSpec.RawRes(inProgressAnimationId))
    val startProgress =
        rememberSaveable(composition, cached.value?.startMarker) {
            composition.progressForMarker(cached.value?.startMarker)
        }
    val endProgress =
        rememberSaveable(composition, cached.value?.endMarker) {
            composition.progressForMarker(cached.value?.endMarker)
        }
    LottieAnimation(
        composition = composition,
        progress = { lerp(start = startProgress, stop = endProgress, fraction = progress) },
        dynamicProperties = animationProperties,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun LottieComposition?.progressForMarker(marker: String?): Float {
    if (marker == null) return 0f
    val startFrame = this?.getMarker(marker)?.startFrame ?: 0f
    return this?.getProgressForFrame(startFrame) ?: 0f
}
