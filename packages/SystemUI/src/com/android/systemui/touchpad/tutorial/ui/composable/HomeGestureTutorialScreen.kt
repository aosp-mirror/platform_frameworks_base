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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialScreenConfig
import com.android.systemui.inputdevice.tutorial.ui.composable.rememberColorFilterProperty
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.HomeGestureMonitor

@Composable
fun HomeGestureTutorialScreen(
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    val screenConfig =
        TutorialScreenConfig(
            colors = rememberScreenColors(),
            strings =
                TutorialScreenConfig.Strings(
                    titleResId = R.string.touchpad_home_gesture_action_title,
                    bodyResId = R.string.touchpad_home_gesture_guidance,
                    titleSuccessResId = R.string.touchpad_home_gesture_success_title,
                    bodySuccessResId = R.string.touchpad_home_gesture_success_body
                ),
            animations =
                TutorialScreenConfig.Animations(
                    educationResId = R.raw.trackpad_home_edu,
                    successResId = R.raw.trackpad_home_success
                )
        )
    val gestureMonitorProvider =
        DistanceBasedGestureMonitorProvider(
            monitorFactory = { distanceThresholdPx, gestureStateCallback ->
                HomeGestureMonitor(distanceThresholdPx, gestureStateCallback)
            }
        )
    GestureTutorialScreen(screenConfig, gestureMonitorProvider, onDoneButtonClicked, onBack)
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
            rememberColorFilterProperty(".onPrimaryFixedVariant", onPrimaryFixedVariant)
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
