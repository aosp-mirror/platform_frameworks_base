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

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialScreenConfig
import com.android.systemui.inputdevice.tutorial.ui.composable.rememberColorFilterProperty
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureFlowAdapter
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureRecognizer
import com.android.systemui.touchpad.tutorial.ui.gesture.HomeGestureRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
fun HomeGestureTutorialScreen(onDoneButtonClicked: () -> Unit, onBack: () -> Unit) {
    val screenConfig =
        TutorialScreenConfig(
            colors = rememberScreenColors(),
            strings =
                TutorialScreenConfig.Strings(
                    titleResId = R.string.touchpad_home_gesture_action_title,
                    bodyResId = R.string.touchpad_home_gesture_guidance,
                    titleSuccessResId = R.string.touchpad_home_gesture_success_title,
                    bodySuccessResId = R.string.touchpad_home_gesture_success_body,
                ),
            animations = TutorialScreenConfig.Animations(educationResId = R.raw.trackpad_home_edu),
        )
    val recognizer = rememberHomeGestureRecognizer(LocalContext.current.resources)
    val gestureUiState: Flow<GestureUiState> =
        remember(recognizer) {
            GestureFlowAdapter(recognizer).gestureStateAsFlow.map {
                it.toGestureUiState(
                    progressStartMarker = "drag with gesture",
                    progressEndMarker = "release playback realtime",
                    successAnimation = R.raw.trackpad_home_success,
                )
            }
        }
    GestureTutorialScreen(screenConfig, recognizer, gestureUiState, onDoneButtonClicked, onBack)
}

@Composable
private fun rememberHomeGestureRecognizer(resources: Resources): GestureRecognizer {
    val distance =
        resources.getDimensionPixelSize(R.dimen.touchpad_tutorial_gestures_distance_threshold)
    val velocity = resources.getDimension(R.dimen.touchpad_home_gesture_velocity_threshold)
    return remember(distance) { HomeGestureRecognizer(distance, velocity) }
}

@Composable
private fun rememberScreenColors(): TutorialScreenConfig.Colors {
    val primaryFixedDim = LocalAndroidColorScheme.current.primaryFixedDim
    val onPrimaryFixed = LocalAndroidColorScheme.current.onPrimaryFixed
    val onPrimaryFixedVariant = LocalAndroidColorScheme.current.onPrimaryFixedVariant
    val dynamicProperties =
        rememberLottieDynamicProperties(
            rememberColorFilterProperty(".primaryFixedDim", primaryFixedDim),
            rememberColorFilterProperty(".onPrimaryFixed", onPrimaryFixed),
            rememberColorFilterProperty(".onPrimaryFixedVariant", onPrimaryFixedVariant),
        )
    val screenColors =
        remember(dynamicProperties) {
            TutorialScreenConfig.Colors(
                background = onPrimaryFixed,
                title = primaryFixedDim,
                animationColors = dynamicProperties,
            )
        }
    return screenColors
}
