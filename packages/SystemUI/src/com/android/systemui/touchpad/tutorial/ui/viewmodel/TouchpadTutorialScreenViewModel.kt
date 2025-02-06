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

package com.android.systemui.touchpad.tutorial.ui.viewmodel

import android.view.MotionEvent
import androidx.annotation.RawRes
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.Finished
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NotStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface TouchpadTutorialScreenViewModel {
    val tutorialState: Flow<TutorialActionState>

    fun handleEvent(event: MotionEvent): Boolean
}

data class TutorialAnimationProperties(
    val progressStartMarker: String,
    val progressEndMarker: String,
    @RawRes val successAnimation: Int,
)

fun Flow<Pair<GestureState, TutorialAnimationProperties>>.mapToTutorialState():
    Flow<TutorialActionState> {
    return flow<TutorialActionState> {
        var lastState: TutorialActionState = TutorialActionState.NotStarted
        collect { (gestureState, animationProperties) ->
            val newState = gestureState.toTutorialActionState(animationProperties, lastState)
            lastState = newState
            emit(newState)
        }
    }
}

fun GestureState.toTutorialActionState(
    properties: TutorialAnimationProperties,
    previousState: TutorialActionState,
): TutorialActionState {
    return when (this) {
        NotStarted -> TutorialActionState.NotStarted
        is InProgress -> {
            val inProgress =
                TutorialActionState.InProgress(
                    progress = progress,
                    startMarker = properties.progressStartMarker,
                    endMarker = properties.progressEndMarker,
                )
            if (
                previousState is TutorialActionState.InProgressAfterError ||
                    previousState is TutorialActionState.Error
            ) {
                TutorialActionState.InProgressAfterError(inProgress)
            } else {
                inProgress
            }
        }
        is Finished -> TutorialActionState.Finished(properties.successAnimation)
        GestureState.Error -> TutorialActionState.Error
    }
}
