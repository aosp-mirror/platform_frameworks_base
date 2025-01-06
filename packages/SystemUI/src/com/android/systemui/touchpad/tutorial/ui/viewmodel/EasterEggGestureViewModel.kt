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
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.handleTouchpadMotionEvent
import java.util.function.Consumer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

class EasterEggGestureViewModel(val gestureRecognizer: GestureRecognizerAdapter) :
    Consumer<MotionEvent> {

    private val gestureDone = gestureRecognizer.gestureState.filter { it == GestureState.Finished }

    private val easterEggFinished = Channel<Unit>()

    val easterEggTriggered =
        merge(
                gestureDone.map { Event.GestureFinished },
                easterEggFinished.receiveAsFlow().map { Event.StateRestarted },
            )
            .map {
                when (it) {
                    Event.GestureFinished -> true
                    Event.StateRestarted -> false
                }
            }
            .onStart { emit(false) }

    override fun accept(event: MotionEvent) {
        gestureRecognizer.handleTouchpadMotionEvent(event)
    }

    fun onEasterEggFinished() {
        easterEggFinished.trySend(Unit)
    }

    private sealed interface Event {
        data object GestureFinished : Event

        data object StateRestarted : Event
    }
}
