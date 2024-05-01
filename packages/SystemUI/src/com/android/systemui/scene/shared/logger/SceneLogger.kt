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

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.SceneFrameworkLog
import java.util.Stack
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
    ) {
        logBuffer.log(
            tag = TAG,
            level = LogLevel.INFO,
            messageInitializer = {
                str1 = from.toString()
                str2 = to.toString()
                str3 = reason
            },
            messagePrinter = { "Scene change requested: $str1 → $str2, reason: $str3" },
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

    fun logSceneBackStack(backStack: Stack<SceneKey>) {
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
