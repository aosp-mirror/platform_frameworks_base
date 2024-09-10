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

package com.android.systemui.communal.shared.log

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject

class CommunalSceneLogger @Inject constructor(@CommunalLog private val logBuffer: LogBuffer) {

    fun logSceneChangeRequested(
        from: SceneKey,
        to: SceneKey,
        reason: String,
        isInstant: Boolean,
    ) {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = {
                str1 = from.toString()
                str2 = to.toString()
                str3 = reason
                bool1 = isInstant
            },
            messagePrinter = {
                buildString {
                    append("Scene change requested: $str1 → $str2")
                    if (isInstant) {
                        append(" (instant)")
                    }
                    append(", reason: $str3")
                }
            },
        )
    }

    fun logSceneChangeCommitted(
        from: SceneKey,
        to: SceneKey,
    ) {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = {
                str1 = from.toString()
                str2 = to.toString()
            },
            messagePrinter = { "Scene change committed: $str1 → $str2" },
        )
    }

    fun logSceneTransition(transitionState: ObservableTransitionState) {
        when (transitionState) {
            is ObservableTransitionState.Transition -> {
                logBuffer.log(
                    tag = TAG,
                    level = LogLevel.INFO,
                    messageInitializer = {
                        str1 = transitionState.fromContent.toString()
                        str2 = transitionState.toContent.toString()
                    },
                    messagePrinter = { "Scene transition started: $str1 → $str2" },
                )
            }
            is ObservableTransitionState.Idle -> {
                logBuffer.log(
                    tag = TAG,
                    level = LogLevel.INFO,
                    messageInitializer = { str1 = transitionState.currentScene.toString() },
                    messagePrinter = { "Scene transition idle on: $str1" },
                )
            }
        }
    }

    companion object {
        private const val TAG = "CommunalSceneLogger"
    }
}
