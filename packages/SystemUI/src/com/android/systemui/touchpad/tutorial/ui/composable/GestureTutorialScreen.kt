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

package com.android.systemui.touchpad.tutorial.ui.composable

import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
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
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.IN_PROGRESS
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGestureHandler
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGestureMonitor

interface GestureMonitorProvider {
    fun createGestureMonitor(
        gestureDistanceThresholdPx: Int,
        gestureStateChangedCallback: (GestureState) -> Unit
    ): TouchpadGestureMonitor
}

@Composable
fun GestureTutorialScreen(
    screenConfig: TutorialScreenConfig,
    gestureMonitorProvider: GestureMonitorProvider,
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var gestureState by remember { mutableStateOf(NOT_STARTED) }
    val swipeDistanceThresholdPx =
        LocalContext.current.resources.getDimensionPixelSize(
            com.android.internal.R.dimen.system_gestures_distance_threshold
        )
    val gestureHandler =
        remember(swipeDistanceThresholdPx) {
            TouchpadGestureHandler(
                gestureMonitorProvider.createGestureMonitor(
                    swipeDistanceThresholdPx,
                    gestureStateChangedCallback = { gestureState = it }
                )
            )
        }
    TouchpadGesturesHandlingBox(gestureHandler, gestureState) {
        GestureTutorialContent(gestureState, onDoneButtonClicked, screenConfig)
    }
}

@Composable
private fun TouchpadGesturesHandlingBox(
    gestureHandler: TouchpadGestureHandler,
    gestureState: GestureState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // we need to use pointerInteropFilter because some info about touchpad gestures is
                // only available in MotionEvent
                .pointerInteropFilter(
                    onTouchEvent = { event ->
                        // FINISHED is the final state so we don't need to process touches anymore
                        if (gestureState == FINISHED) {
                            false
                        } else {
                            gestureHandler.onMotionEvent(event)
                        }
                    }
                )
    ) {
        content()
    }
}

@Composable
private fun GestureTutorialContent(
    gestureState: GestureState,
    onDoneButtonClicked: () -> Unit,
    config: TutorialScreenConfig
) {
    val animatedColor by
        animateColorAsState(
            targetValue =
                if (gestureState == FINISHED) config.colors.successBackground
                else config.colors.background,
            animationSpec = tween(durationMillis = 150, easing = LinearEasing),
            label = "backgroundColor"
        )
    Column(
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.fillMaxSize()
                .drawBehind { drawRect(animatedColor) }
                .padding(start = 48.dp, top = 124.dp, end = 48.dp, bottom = 48.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TutorialDescription(
                titleTextId =
                    if (gestureState == FINISHED) config.strings.titleSuccessResId
                    else config.strings.titleResId,
                titleColor = config.colors.title,
                bodyTextId =
                    if (gestureState == FINISHED) config.strings.bodySuccessResId
                    else config.strings.bodyResId,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(76.dp))
            TutorialAnimation(
                gestureState,
                config,
                modifier = Modifier.weight(1f).padding(top = 8.dp)
            )
        }
        DoneButton(onDoneButtonClicked = onDoneButtonClicked)
    }
}

@Composable
fun TutorialDescription(
    @StringRes titleTextId: Int,
    titleColor: Color,
    @StringRes bodyTextId: Int,
    modifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.Top, modifier = modifier) {
        Text(
            text = stringResource(id = titleTextId),
            style = MaterialTheme.typography.displayLarge,
            color = titleColor
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = bodyTextId),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

@Composable
fun TutorialAnimation(
    gestureState: GestureState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = gestureState,
            transitionSpec = {
                if (initialState == NOT_STARTED && targetState == IN_PROGRESS) {
                    val transitionDurationMillis = 150
                    fadeIn(
                        animationSpec = tween(transitionDurationMillis, easing = LinearEasing)
                    ) togetherWith
                        fadeOut(animationSpec = snap(delayMillis = transitionDurationMillis))
                } else {
                    // empty transition works because all remaining transitions are from IN_PROGRESS
                    // state which shares initial animation frame with both FINISHED and NOT_STARTED
                    EnterTransition.None togetherWith ExitTransition.None
                }
            }
        ) { gestureState ->
            when (gestureState) {
                NOT_STARTED ->
                    EducationAnimation(
                        config.animations.educationResId,
                        config.colors.animationColors
                    )
                IN_PROGRESS ->
                    FrozenSuccessAnimation(
                        config.animations.successResId,
                        config.colors.animationColors
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
    animationProperties: LottieDynamicProperties
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
    animationProperties: LottieDynamicProperties
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
    animationProperties: LottieDynamicProperties
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
    color: Color
): LottieDynamicProperty<ColorFilter> {
    return rememberLottieDynamicProperty(
        LottieProperty.COLOR_FILTER,
        value = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_ATOP),
        // "**" below means match zero or more layers, so ** layerName ** means find layer with that
        // name at any depth
        keyPath = arrayOf("**", layerName, "**")
    )
}
