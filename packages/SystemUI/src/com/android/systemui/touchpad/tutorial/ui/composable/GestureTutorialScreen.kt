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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.inputdevice.tutorial.ui.composable.ActionTutorialContent
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialScreenConfig
import com.android.systemui.touchpad.tutorial.ui.gesture.EasterEggGestureMonitor
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.IN_PROGRESS
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGestureHandler
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGestureMonitor

interface GestureMonitorProvider {

    @Composable
    fun rememberGestureMonitor(
        resources: Resources,
        gestureStateChangedCallback: (GestureState) -> Unit,
    ): TouchpadGestureMonitor
}

typealias gestureStateCallback = (GestureState) -> Unit

class DistanceBasedGestureMonitorProvider(
    val monitorFactory: (Int, gestureStateCallback) -> TouchpadGestureMonitor
) : GestureMonitorProvider {

    @Composable
    override fun rememberGestureMonitor(
        resources: Resources,
        gestureStateChangedCallback: (GestureState) -> Unit,
    ): TouchpadGestureMonitor {
        val distanceThresholdPx =
            resources.getDimensionPixelSize(
                com.android.internal.R.dimen.system_gestures_distance_threshold
            )
        return remember(distanceThresholdPx) {
            monitorFactory(distanceThresholdPx, gestureStateChangedCallback)
        }
    }
}

fun GestureState.toTutorialActionState(): TutorialActionState {
    return when (this) {
        NOT_STARTED -> TutorialActionState.NOT_STARTED
        IN_PROGRESS -> TutorialActionState.IN_PROGRESS
        FINISHED -> TutorialActionState.FINISHED
    }
}

@Composable
fun GestureTutorialScreen(
    screenConfig: TutorialScreenConfig,
    gestureMonitorProvider: GestureMonitorProvider,
    onDoneButtonClicked: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var gestureState by remember { mutableStateOf(NOT_STARTED) }
    var easterEggTriggered by remember { mutableStateOf(false) }
    val gestureMonitor =
        gestureMonitorProvider.rememberGestureMonitor(
            resources = LocalContext.current.resources,
            gestureStateChangedCallback = { gestureState = it },
        )
    val easterEggMonitor = EasterEggGestureMonitor { easterEggTriggered = true }
    val gestureHandler =
        remember(gestureMonitor) { TouchpadGestureHandler(gestureMonitor, easterEggMonitor) }
    TouchpadGesturesHandlingBox(
        gestureHandler,
        gestureState,
        easterEggTriggered,
        resetEasterEggFlag = { easterEggTriggered = false },
    ) {
        ActionTutorialContent(
            gestureState.toTutorialActionState(),
            onDoneButtonClicked,
            screenConfig,
        )
    }
}

@Composable
private fun TouchpadGesturesHandlingBox(
    gestureHandler: TouchpadGestureHandler,
    gestureState: GestureState,
    easterEggTriggered: Boolean,
    resetEasterEggFlag: () -> Unit,
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
            resetEasterEggFlag()
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
                        if (gestureState == FINISHED) {
                            false
                        } else {
                            gestureHandler.onMotionEvent(event)
                        }
                    }
                )
                .graphicsLayer { rotationZ = rotationAnimation.value }
    ) {
        content()
    }
}
