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

import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.LottieDynamicProperty
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.compose.modifiers.background
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.FINISHED
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.IN_PROGRESS
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NOT_STARTED

enum class TutorialActionState {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED,
}

@Composable
fun ActionTutorialContent(
    actionState: TutorialActionState,
    onDoneButtonClicked: () -> Unit,
    config: TutorialScreenConfig,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.fillMaxSize()
                .background(config.colors.background)
                .safeDrawingPadding()
                .padding(start = 48.dp, top = 100.dp, end = 48.dp, bottom = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TutorialDescription(
                titleTextId =
                    if (actionState == FINISHED) config.strings.titleSuccessResId
                    else config.strings.titleResId,
                titleColor = config.colors.title,
                bodyTextId =
                    if (actionState == FINISHED) config.strings.bodySuccessResId
                    else config.strings.bodyResId,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(76.dp))
            TutorialAnimation(
                actionState,
                config,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
        }
        AnimatedVisibility(visible = actionState == FINISHED, enter = fadeIn()) {
            DoneButton(onDoneButtonClicked = onDoneButtonClicked)
        }
    }
}

@Composable
fun TutorialDescription(
    @StringRes titleTextId: Int,
    titleColor: Color,
    @StringRes bodyTextId: Int,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.Top, modifier = modifier) {
        Text(
            text = stringResource(id = titleTextId),
            style = MaterialTheme.typography.displayLarge,
            color = titleColor,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = bodyTextId),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
    }
}

@Composable
fun TutorialAnimation(
    actionState: TutorialActionState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = actionState,
            transitionSpec = {
                if (initialState == NOT_STARTED) {
                    val transitionDurationMillis = 150
                    fadeIn(animationSpec = tween(transitionDurationMillis, easing = LinearEasing))
                        .togetherWith(
                            fadeOut(animationSpec = snap(delayMillis = transitionDurationMillis))
                        )
                        // we explicitly don't want size transform because when targetState
                        // animation is loaded for the first time, AnimatedContent thinks target
                        // size is smaller and tries to shrink initial state animation
                        .using(sizeTransform = null)
                } else {
                    // empty transition works because all remaining transitions are from IN_PROGRESS
                    // state which shares initial animation frame with both FINISHED and NOT_STARTED
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
        ) { state ->
            when (state) {
                NOT_STARTED ->
                    EducationAnimation(
                        config.animations.educationResId,
                        config.colors.animationColors,
                    )
                IN_PROGRESS ->
                    FrozenSuccessAnimation(
                        config.animations.successResId,
                        config.colors.animationColors,
                    )
                FINISHED ->
                    SuccessAnimation(config.animations.successResId, config.colors.animationColors)
            }
        }
    }
}

@Composable
private fun FrozenSuccessAnimation(
    @RawRes successAnimationId: Int,
    animationProperties: LottieDynamicProperties,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(successAnimationId))
    LottieAnimation(
        composition = composition,
        progress = { 0f }, // animation should freeze on 1st frame
        dynamicProperties = animationProperties,
    )
}

@Composable
private fun EducationAnimation(
    @RawRes educationAnimationId: Int,
    animationProperties: LottieDynamicProperties,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(educationAnimationId))
    val progress by
        animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = animationProperties,
    )
}

@Composable
private fun SuccessAnimation(
    @RawRes successAnimationId: Int,
    animationProperties: LottieDynamicProperties,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(successAnimationId))
    val progress by animateLottieCompositionAsState(composition, iterations = 1)
    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = animationProperties,
    )
}

@Composable
fun rememberColorFilterProperty(
    layerName: String,
    color: Color,
): LottieDynamicProperty<ColorFilter> {
    return rememberLottieDynamicProperty(
        LottieProperty.COLOR_FILTER,
        value = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_ATOP),
        // "**" below means match zero or more layers, so ** layerName ** means find layer with that
        // name at any depth
        keyPath = arrayOf("**", layerName, "**"),
    )
}
