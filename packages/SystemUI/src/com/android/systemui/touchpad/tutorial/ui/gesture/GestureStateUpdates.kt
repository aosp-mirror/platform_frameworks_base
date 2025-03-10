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

package com.android.systemui.touchpad.tutorial.ui.gesture

/** Helper function for gesture recognizers to have common state triggering logic */
inline fun updateGestureState(
    gestureStateChangedCallback: (GestureState) -> Unit,
    gestureState: DistanceGestureState?,
    isFinished: (Finished) -> Boolean,
    progress: (Moving) -> GestureState.InProgress,
) {
    when (gestureState) {
        is Finished -> {
            if (isFinished(gestureState)) {
                gestureStateChangedCallback(GestureState.Finished)
            } else {
                gestureStateChangedCallback(GestureState.NotStarted)
            }
        }
        is Moving -> {
            gestureStateChangedCallback(progress(gestureState))
        }
        is Started -> gestureStateChangedCallback(GestureState.InProgress())
        else -> {}
    }
}
