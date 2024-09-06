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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

class FakeScene(
    val scope: CoroutineScope,
    override val key: SceneKey,
) : Scene {
    var isDestinationScenesBeingCollected = false

    private val destinationScenesChannel = Channel<Map<UserAction, UserActionResult>>()

    override val destinationScenes =
        destinationScenesChannel
            .receiveAsFlow()
            .onStart { isDestinationScenesBeingCollected = true }
            .onCompletion { isDestinationScenesBeingCollected = false }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyMap(),
            )

    suspend fun setDestinationScenes(value: Map<UserAction, UserActionResult>) {
        destinationScenesChannel.send(value)
    }
}
