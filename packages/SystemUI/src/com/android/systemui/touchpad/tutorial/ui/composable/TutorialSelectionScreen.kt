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

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.android.systemui.inputdevice.tutorial.ui.composable.DoneButton
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.isFourFingerTouchpadSwipe
import com.android.systemui.touchpad.tutorial.ui.gesture.isThreeFingerTouchpadSwipe

@Composable
fun TutorialSelectionScreen(
    onBackTutorialClicked: () -> Unit,
    onHomeTutorialClicked: () -> Unit,
    onRecentAppsTutorialClicked: () -> Unit,
    onDoneButtonClicked: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)
                .fillMaxSize()
                .safeDrawingPadding()
                .pointerInteropFilter(
                    onTouchEvent = { event ->
                        // Because of window flag we're intercepting 3 and 4-finger swipes.
                        // Although we don't handle them in this screen, we want to disable them so
                        // that user is not clicking button by mistake by performing these swipes.
                        isThreeFingerTouchpadSwipe(event) || isFourFingerTouchpadSwipe(event)
                    }
                ),
    ) {
        val configuration = LocalConfiguration.current
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                HorizontalSelectionButtons(
                    onBackTutorialClicked = onBackTutorialClicked,
                    onHomeTutorialClicked = onHomeTutorialClicked,
                    onRecentAppsTutorialClicked = onRecentAppsTutorialClicked,
                    modifier = Modifier.weight(1f).padding(60.dp),
                )
            }
            else -> {
                VerticalSelectionButtons(
                    onBackTutorialClicked = onBackTutorialClicked,
                    onHomeTutorialClicked = onHomeTutorialClicked,
                    onRecentAppsTutorialClicked = onRecentAppsTutorialClicked,
                    modifier = Modifier.weight(1f).padding(60.dp),
                )
            }
        }
        // because other composables have weight 1, Done button will be positioned first
        DoneButton(
            onDoneButtonClicked = onDoneButtonClicked,
            modifier = Modifier.padding(horizontal = 60.dp),
        )
    }
}

@Composable
private fun HorizontalSelectionButtons(
    onBackTutorialClicked: () -> Unit,
    onHomeTutorialClicked: () -> Unit,
    onRecentAppsTutorialClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ThreeTutorialButtons(
            onBackTutorialClicked,
            onHomeTutorialClicked,
            onRecentAppsTutorialClicked,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

@Composable
private fun VerticalSelectionButtons(
    onBackTutorialClicked: () -> Unit,
    onHomeTutorialClicked: () -> Unit,
    onRecentAppsTutorialClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        ThreeTutorialButtons(
            onBackTutorialClicked,
            onHomeTutorialClicked,
            onRecentAppsTutorialClicked,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

@Composable
private fun ThreeTutorialButtons(
    onBackTutorialClicked: () -> Unit,
    onHomeTutorialClicked: () -> Unit,
    onRecentAppsTutorialClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TutorialButton(
        text = stringResource(R.string.touchpad_tutorial_home_gesture_button),
        icon = ImageVector.vectorResource(id = R.drawable.touchpad_tutorial_home_icon),
        iconColor = MaterialTheme.colorScheme.onPrimary,
        onClick = onHomeTutorialClicked,
        backgroundColor = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
    TutorialButton(
        text = stringResource(R.string.touchpad_tutorial_back_gesture_button),
        icon = Icons.AutoMirrored.Outlined.ArrowBack,
        iconColor = MaterialTheme.colorScheme.onTertiary,
        onClick = onBackTutorialClicked,
        backgroundColor = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
    TutorialButton(
        text = stringResource(R.string.touchpad_tutorial_recent_apps_gesture_button),
        icon = ImageVector.vectorResource(id = R.drawable.touchpad_tutorial_recents_icon),
        iconColor = MaterialTheme.colorScheme.onSecondary,
        onClick = onRecentAppsTutorialClicked,
        backgroundColor = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    )
}

@Composable
private fun TutorialButton(
    text: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.width(30.dp).height(30.dp),
                tint = iconColor,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = text, style = MaterialTheme.typography.headlineLarge)
        }
    }
}
