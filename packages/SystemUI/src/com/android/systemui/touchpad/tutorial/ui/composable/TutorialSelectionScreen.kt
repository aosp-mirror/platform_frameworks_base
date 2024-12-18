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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.inputdevice.tutorial.ui.composable.DoneButton
import com.android.systemui.res.R

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
            Modifier.background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                )
                .fillMaxSize()
    ) {
        TutorialSelectionButtons(
            onBackTutorialClicked = onBackTutorialClicked,
            onHomeTutorialClicked = onHomeTutorialClicked,
            onRecentAppsTutorialClicked = onRecentAppsTutorialClicked,
            modifier = Modifier.padding(60.dp)
        )
        DoneButton(
            onDoneButtonClicked = onDoneButtonClicked,
            modifier = Modifier.padding(horizontal = 60.dp)
        )
    }
}

@Composable
private fun TutorialSelectionButtons(
    onBackTutorialClicked: () -> Unit,
    onHomeTutorialClicked: () -> Unit,
    onRecentAppsTutorialClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        TutorialButton(
            text = stringResource(R.string.touchpad_tutorial_home_gesture_button),
            onClick = onHomeTutorialClicked,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        TutorialButton(
            text = stringResource(R.string.touchpad_tutorial_back_gesture_button),
            onClick = onBackTutorialClicked,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
        TutorialButton(
            text = stringResource(R.string.touchpad_tutorial_recent_apps_gesture_button),
            onClick = onRecentAppsTutorialClicked,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TutorialButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier.aspectRatio(0.66f)
    ) {
        Text(text = text, style = MaterialTheme.typography.headlineLarge)
    }
}
