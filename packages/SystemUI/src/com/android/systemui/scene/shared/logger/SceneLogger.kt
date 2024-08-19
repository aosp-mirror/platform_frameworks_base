/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.shared.logger

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.SceneFrameworkLog
import javax.inject.Inject

class SceneLogger @Inject constructor(@SceneFrameworkLog private val logBuffer: LogBuffer) {

    fun logFrameworkEnabled(isEnabled: Boolean, reason: String? = null) {
        fun asWord(isEnabled: Boolean): String {
            return if (isEnabled) "enabled" else "disabled"
        }

        logBuffer.log(
            tag = TAG,
            level = if (isEnabled) LogLevel.INFO else LogLevel.WARNING,
            messageInitializer = {
                bool1 = isEnabled
                str1 = reason
            },
            messagePrinter = {
                "Scene framework is ${asWord(bool1)}${if (str1 != null) " $str1" else ""}"
            }
        )
    }

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
                        str1 = transitionState.fromScene.toString()
                        str2 = transitionState.toScene.toString()
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

    fun logVisibilityChange(
        from: Boolean,
        to: Boolean,
        reason: String,
    ) {
        fun asWord(isVisible: Boolean): String {
            return if (isVisible) "visible" else "invisible"
        }

        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = {
                str1 = asWord(from)
                str2 = asWord(to)
                str3 = reason
            },
            messagePrinter = { "$str1 → $str2, reason: $str3" },
        )
    }

    fun logRemoteUserInteractionStarted(
        reason: String,
    ) {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = { str1 = reason },
            messagePrinter = { "remote user interaction started, reason: $str1" },
        )
    }

    fun logUserInteractionFinished() {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = {},
            messagePrinter = { "user interaction finished" },
        )
    }

    fun logSceneBackStack(backStack: Iterable<SceneKey>) {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = { str1 = backStack.joinToString(", ") { it.debugName } },
            messagePrinter = { "back stack: $str1" },
        )
    }

    companion object {
        private const val TAG = "SceneFramework"
    }
}
