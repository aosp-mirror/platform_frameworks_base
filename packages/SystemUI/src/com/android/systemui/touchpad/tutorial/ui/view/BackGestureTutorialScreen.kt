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

package com.android.systemui.touchpad.tutorial.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R

@Composable
fun BackGestureTutorialScreen(
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .background(color = MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 48.dp, top = 124.dp, end = 48.dp, bottom = 48.dp)
                .fillMaxSize()
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TutorialDescription(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(76.dp))
            TutorialAnimation(modifier = Modifier.weight(1f).padding(top = 24.dp))
        }
        DoneButton(onDoneButtonClicked = onDoneButtonClicked)
    }
}

@Composable
fun TutorialDescription(modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.Top, modifier = modifier) {
        Text(
            text = stringResource(id = R.string.touchpad_back_gesture_action_title),
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.touchpad_back_gesture_guidance),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun TutorialAnimation(modifier: Modifier = Modifier) {
    // below are just placeholder images, will be substituted by animations soon
    Column(modifier = modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(id = R.drawable.placeholder_touchpad_tablet_back_gesture),
            contentDescription =
                stringResource(
                    id = R.string.touchpad_back_gesture_screen_animation_content_description
                ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Image(
            painter = painterResource(id = R.drawable.placeholder_touchpad_back_gesture),
            contentDescription =
                stringResource(id = R.string.touchpad_back_gesture_animation_content_description),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
