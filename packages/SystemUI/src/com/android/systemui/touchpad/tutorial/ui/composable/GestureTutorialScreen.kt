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

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.inputdevice.tutorial.ui.composable.ActionTutorialContent
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NotStarted
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialScreenConfig
import kotlinx.coroutines.flow.Flow

@Composable
fun GestureTutorialScreen(
    screenConfig: TutorialScreenConfig,
    tutorialStateFlow: Flow<TutorialActionState>,
    motionEventConsumer: (MotionEvent) -> Boolean,
    easterEggTriggeredFlow: Flow<Boolean>,
    onEasterEggFinished: () -> Unit,
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var cachedTutorialState: TutorialActionState by
        rememberSaveable(stateSaver = TutorialActionState.stateSaver()) {
            mutableStateOf(NotStarted)
        }
    val easterEggTriggered by easterEggTriggeredFlow.collectAsStateWithLifecycle(false)
    val tutorialState by tutorialStateFlow.collectAsStateWithLifecycle(cachedTutorialState)
    cachedTutorialState = tutorialState
    TouchpadGesturesHandlingBox(
        motionEventConsumer,
        tutorialState,
        easterEggTriggered,
        onEasterEggFinished,
    ) {
        ActionTutorialContent(tutorialState, onDoneButtonClicked, screenConfig)
    }
}

@Composable
private fun TouchpadGesturesHandlingBox(
    motionEventConsumer: (MotionEvent) -> Boolean,
    tutorialState: TutorialActionState,
    easterEggTriggered: Boolean,
    onEasterEggFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val rotationAnimation = remember { Animatable(0f) }
    LaunchedEffect(easterEggTriggered) {
        if (easterEggTriggered || rotationAnimation.isRunning) {
            rotationAnimation.snapTo(0f)
            rotationAnimation.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 2000),
            )
            onEasterEggFinished()
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // we need to use pointerInteropFilter because some info about touchpad gestures is
                // only available in MotionEvent
                .pointerInteropFilter(
                    onTouchEvent = { event ->
                        // FINISHED is the final state so we don't need to process touches anymore
                        if (tutorialState is TutorialActionState.Finished) {
                            false
                        } else {
                            motionEventConsumer(event)
                        }
                    }
                )
                .graphicsLayer { rotationZ = rotationAnimation.value }
    ) {
        content()
    }
}
