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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.composable.TutorialActionState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.composable.TutorialActionState.NOT_STARTED

@Composable
fun ActionKeyTutorialScreen(
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val screenConfig = buildScreenConfig()
    var actionState by remember { mutableStateOf(NOT_STARTED) }
    Box(
        modifier =
            Modifier.fillMaxSize().onKeyEvent { keyEvent: KeyEvent ->
                // temporary before we can access Action/Meta key
                if (keyEvent.key == Key.AltLeft && keyEvent.type == KeyEventType.KeyUp) {
                    actionState = FINISHED
                }
                true
            }
    ) {
        ActionTutorialContent(actionState, onDoneButtonClicked, screenConfig)
    }
}

@Composable
private fun buildScreenConfig() =
    TutorialScreenConfig(
        colors = rememberScreenColors(),
        strings =
            TutorialScreenConfig.Strings(
                titleResId = R.string.tutorial_action_key_title,
                bodyResId = R.string.tutorial_action_key_guidance,
                titleSuccessResId = R.string.tutorial_action_key_success_title,
                bodySuccessResId = R.string.tutorial_action_key_success_body
            ),
        animations =
            TutorialScreenConfig.Animations(
                educationResId = R.raw.action_key_edu,
                successResId = R.raw.action_key_success
            )
    )

@Composable
private fun rememberScreenColors(): TutorialScreenConfig.Colors {
    val primaryFixedDim = LocalAndroidColorScheme.current.primaryFixedDim
    val secondaryFixedDim = LocalAndroidColorScheme.current.secondaryFixedDim
    val onSecondaryFixed = LocalAndroidColorScheme.current.onSecondaryFixed
    val onSecondaryFixedVariant = LocalAndroidColorScheme.current.onSecondaryFixedVariant
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val dynamicProperties =
        rememberLottieDynamicProperties(
            rememberColorFilterProperty(".primaryFixedDim", primaryFixedDim),
            rememberColorFilterProperty(".secondaryFixedDim", secondaryFixedDim),
            rememberColorFilterProperty(".onSecondaryFixed", onSecondaryFixed),
            rememberColorFilterProperty(".onSecondaryFixedVariant", onSecondaryFixedVariant)
        )
    val screenColors =
        remember(surfaceContainer, dynamicProperties) {
            TutorialScreenConfig.Colors(
                background = onSecondaryFixed,
                successBackground = surfaceContainer,
                title = primaryFixedDim,
                animationColors = dynamicProperties,
            )
        }
    return screenColors
}
