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
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished

sealed interface TutorialActionState {
    data object NotStarted : TutorialActionState

    data class InProgress(
        val progress: Float = 0f,
        val startMarker: String? = null,
        val endMarker: String? = null,
    ) : TutorialActionState

    data class Finished(@RawRes val successAnimation: Int) : TutorialActionState
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
                    if (actionState is Finished) config.strings.titleSuccessResId
                    else config.strings.titleResId,
                titleColor = config.colors.title,
                bodyTextId =
                    if (actionState is Finished) config.strings.bodySuccessResId
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
        AnimatedVisibility(visible = actionState is Finished, enter = fadeIn()) {
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
