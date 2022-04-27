/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dump

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogcatEchoTracker

/**
 * Creates a LogBuffer that will echo everything to logcat, which is useful for debugging tests.
 */
fun logcatLogBuffer(name: String = "EchoToLogcatLogBuffer") =
    LogBuffer(name, 50, 50, LogcatEchoTrackerAlways())

/**
 * A [LogcatEchoTracker] that always allows echoing to the logcat.
 */
class LogcatEchoTrackerAlways : LogcatEchoTracker {
    override val logInBackgroundThread = false
    override fun isBufferLoggable(bufferName: String, level: LogLevel): Boolean = true
    override fun isTagLoggable(tagName: String, level: LogLevel): Boolean = true
}
