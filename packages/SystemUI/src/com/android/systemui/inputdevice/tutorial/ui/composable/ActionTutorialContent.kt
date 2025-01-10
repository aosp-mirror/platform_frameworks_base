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

import android.content.res.Configuration
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Error
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgress
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgressAfterError
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NotStarted

sealed interface TutorialActionState {
    data object NotStarted : TutorialActionState

    data class InProgress(
        override val progress: Float = 0f,
        override val startMarker: String? = null,
        override val endMarker: String? = null,
    ) : TutorialActionState, Progress

    data class Finished(@RawRes val successAnimation: Int) : TutorialActionState

    data object Error : TutorialActionState

    data class InProgressAfterError(val inProgress: InProgress) :
        TutorialActionState, Progress by inProgress

    companion object {
        fun stateSaver(): Saver<TutorialActionState, Any> {
            val classKey = "class"
            val successAnimationKey = "animation"
            return mapSaver(
                save = {
                    buildMap {
                        put(classKey, it::class.java.name)
                        if (it is Finished) put(successAnimationKey, it.successAnimation)
                    }
                },
                restore = { map ->
                    when (map[classKey] as? String) {
                        NotStarted::class.java.name,
                        InProgress::class.java.name -> NotStarted
                        Error::class.java.name,
                        InProgressAfterError::class.java.name -> Error
                        Finished::class.java.name -> Finished(map[successAnimationKey]!! as Int)
                        else -> NotStarted
                    }
                },
            )
        }
    }
}

interface Progress {
    val progress: Float
    val startMarker: String?
    val endMarker: String?
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
        when (LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                HorizontalDescriptionAndAnimation(actionState, config, Modifier.weight(1f))
            }
            else -> {
                VerticalDescriptionAndAnimation(actionState, config, Modifier.weight(1f))
            }
        }
        val buttonAlpha by animateFloatAsState(if (actionState is Finished) 1f else 0f)
        DoneButton(
            onDoneButtonClicked = onDoneButtonClicked,
            modifier = Modifier.graphicsLayer { alpha = buttonAlpha },
            enabled = actionState is Finished,
        )
    }
}

@Composable
private fun HorizontalDescriptionAndAnimation(
    actionState: TutorialActionState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        TutorialDescription(actionState, config, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(70.dp))
        TutorialAnimation(actionState, config, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VerticalDescriptionAndAnimation(
    actionState: TutorialActionState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp)) {
        Spacer(modifier = Modifier.weight(0.1f))
        TutorialDescription(
            actionState,
            config,
            modifier =
                Modifier.weight(0.2f)
                    // extra padding to better align with animation which has embedded padding
                    .padding(horizontal = 15.dp),
        )
        Spacer(modifier = Modifier.width(70.dp))
        TutorialAnimation(actionState, config, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TutorialDescription(
    actionState: TutorialActionState,
    config: TutorialScreenConfig,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val (titleTextId, bodyTextId) =
        when (actionState) {
            is Finished -> config.strings.titleSuccessResId to config.strings.bodySuccessResId
            Error,
            is InProgressAfterError ->
                config.strings.titleErrorResId to config.strings.bodyErrorResId
            is NotStarted,
            is InProgress -> config.strings.titleResId to config.strings.bodyResId
        }
    Column(verticalArrangement = Arrangement.Top, modifier = modifier) {
        Text(
            text = stringResource(id = titleTextId),
            style = MaterialTheme.typography.displayLarge,
            color = config.colors.title,
            modifier = Modifier.focusRequester(focusRequester).focusable(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = bodyTextId),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
    }
}
