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

package com.android.systemui.shared.clocks

import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogcatOnlyMessageBuffer
import com.android.systemui.log.core.Logger

object LogUtil {
    // Used when MessageBuffers are not provided by the host application
    val DEFAULT_MESSAGE_BUFFER = LogcatOnlyMessageBuffer(LogLevel.INFO)

    // Only intended for use during initialization steps where the correct logger doesn't exist yet
    val FALLBACK_INIT_LOGGER = Logger(LogcatOnlyMessageBuffer(LogLevel.ERROR), "CLOCK_INIT")

    // Debug is primarially used for tests, but can also be used for tracking down hard issues.
    val DEBUG_MESSAGE_BUFFER = LogcatOnlyMessageBuffer(LogLevel.DEBUG)
}
