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
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureRecognizer
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.function.Consumer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Adapter for [GestureRecognizer] exposing [GestureState] as Flow and ensuring that motion events
 * are always handled by latest [GestureRecognizer].
 */
class GestureRecognizerAdapter
@AssistedInject
constructor(
    @Assisted provider: GestureRecognizerProvider,
    private val logger: InputDeviceTutorialLogger,
) : Consumer<MotionEvent> {

    private var gestureRecognizer: GestureRecognizer? = null

    val gestureState: Flow<GestureState> =
        provider.recognizer.flatMapLatest {
            gestureRecognizer = it
            gestureStateAsFlow(it)
        }

    override fun accept(event: MotionEvent) {
        if (gestureRecognizer == null) {
            logger.w("sending MotionEvent before gesture recognizer is initialized")
        } else {
            gestureRecognizer?.accept(event)
        }
    }

    private fun gestureStateAsFlow(recognizer: GestureRecognizer): Flow<GestureState> =
        conflatedCallbackFlow {
            val callback: (GestureState) -> Unit = { trySend(it) }
            recognizer.addGestureStateCallback(callback)
            awaitClose { recognizer.clearGestureStateCallback() }
        }

    @AssistedFactory
    interface Factory {
        fun create(provider: GestureRecognizerProvider): GestureRecognizerAdapter
    }
}
