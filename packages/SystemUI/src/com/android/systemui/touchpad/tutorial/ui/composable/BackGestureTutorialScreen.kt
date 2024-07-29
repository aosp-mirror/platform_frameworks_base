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
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGesture.BACK
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGestureHandler

data class TutorialScreenColors(
    val backgroundColor: Color,
    val successBackgroundColor: Color,
    val titleColor: Color,
    val animationProperties: LottieDynamicProperties
)

@Composable
fun BackGestureTutorialScreen(
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    val screenColors = rememberScreenColors()
    BackHandler(onBack = onBack)
    var gestureDone by remember { mutableStateOf(false) }
    val swipeDistanceThresholdPx =
        LocalContext.current.resources.getDimensionPixelSize(
            com.android.internal.R.dimen.system_gestures_distance_threshold
        )
    val gestureHandler =
        remember(swipeDistanceThresholdPx) {
            TouchpadGestureHandler(BACK, swipeDistanceThresholdPx, onDone = { gestureDone = true })
        }
    Box(
        modifier =
            Modifier.fillMaxSize()
                // we need to use pointerInteropFilter because some info about touchpad gestures is
                // only available in MotionEvent
                .pointerInteropFilter(onTouchEvent = gestureHandler::onMotionEvent)
    ) {
        GestureTutorialContent(gestureDone, onDoneButtonClicked, screenColors)
    }
}

@Composable
private fun rememberScreenColors(): TutorialScreenColors {
    val onTertiary = LocalAndroidColorScheme.current.onTertiary
    val onTertiaryFixed = LocalAndroidColorScheme.current.onTertiaryFixed
    val onTertiaryFixedVariant = LocalAndroidColorScheme.current.onTertiaryFixedVariant
    val tertiaryFixedDim = LocalAndroidColorScheme.current.tertiaryFixedDim
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val dynamicProperties =
        rememberLottieDynamicProperties(
            rememberColorFilterProperty(".tertiaryFixedDim", tertiaryFixedDim),
            rememberColorFilterProperty(".onTertiaryFixed", onTertiaryFixed),
            rememberColorFilterProperty(".onTertiary", onTertiary),
            rememberColorFilterProperty(".onTertiaryFixedVariant", onTertiaryFixedVariant)
        )
    val screenColors =
        remember(onTertiaryFixed, surfaceContainer, tertiaryFixedDim, dynamicProperties) {
            TutorialScreenColors(
                backgroundColor = onTertiaryFixed,
                successBackgroundColor = surfaceContainer,
                titleColor = tertiaryFixedDim,
                animationProperties = dynamicProperties,
            )
        }
    return screenColors
}

@Composable
private fun GestureTutorialContent(
    gestureDone: Boolean,
    onDoneButtonClicked: () -> Unit,
    screenColors: TutorialScreenColors
) {
    val animatedColor by
        animateColorAsState(
            targetValue =
                if (gestureDone) screenColors.successBackgroundColor
                else screenColors.backgroundColor,
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
                    if (gestureDone) R.string.touchpad_tutorial_gesture_done
                    else R.string.touchpad_back_gesture_action_title,
                titleColor = screenColors.titleColor,
                bodyTextId = R.string.touchpad_back_gesture_guidance,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(76.dp))
            TutorialAnimation(
                gestureDone,
                screenColors.animationProperties,
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
    gestureDone: Boolean,
    animationProperties: LottieDynamicProperties,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val resId = if (gestureDone) R.raw.trackpad_back_success else R.raw.trackpad_back_edu
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
        val progress by
            animateLottieCompositionAsState(
                composition,
                iterations = if (gestureDone) 1 else LottieConstants.IterateForever
            )
        LottieAnimation(
            composition = composition,
            progress = { progress },
            dynamicProperties = animationProperties
        )
    }
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
