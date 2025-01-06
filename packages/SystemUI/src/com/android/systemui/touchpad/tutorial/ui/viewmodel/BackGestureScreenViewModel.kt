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
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureDirection
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NotStarted
import com.android.systemui.touchpad.tutorial.ui.gesture.handleTouchpadMotionEvent
import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.flow.Flow

class BackGestureScreenViewModel(val gestureRecognizer: GestureRecognizerAdapter) :
    TouchpadTutorialScreenViewModel {

    override val tutorialState: Flow<TutorialActionState> =
        gestureRecognizer.gestureState
            .pairwiseBy(NotStarted) { previous, current ->
                current to toAnimationProperties(current, previous)
            }
            .mapToTutorialState()

    override fun handleEvent(event: MotionEvent): Boolean {
        return gestureRecognizer.handleTouchpadMotionEvent(event)
    }

    private fun toAnimationProperties(
        current: GestureState,
        previous: GestureState,
    ): TutorialAnimationProperties {
        val (startMarker, endMarker) =
            if (current is InProgress && current.direction == GestureDirection.LEFT) {
                "gesture to L" to "end progress L"
            } else "gesture to R" to "end progress R"
        return TutorialAnimationProperties(
            progressStartMarker = startMarker,
            progressEndMarker = endMarker,
            successAnimation = successAnimation(previous),
        )
    }

    private fun successAnimation(previous: GestureState): Int {
        return if (previous is InProgress && previous.direction == GestureDirection.LEFT) {
            R.raw.trackpad_back_success_left
        } else R.raw.trackpad_back_success_right
    }
}
