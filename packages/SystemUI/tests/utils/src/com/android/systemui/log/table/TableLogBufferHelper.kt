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

package com.android.systemui.log.table

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.log.LogcatEchoTrackerAlways
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.time.fakeSystemClock

/**
 * Creates a [TableLogBuffer] that will echo everything to logcat, which is useful for debugging
 * tests.
 */
fun logcatTableLogBuffer(kosmos: Kosmos, name: String = "EchoToLogcatTableLogBuffer") =
    logcatTableLogBuffer(kosmos.fakeSystemClock, name)

/**
 * Creates a [TableLogBuffer] that will echo everything to logcat, which is useful for debugging
 * tests.
 */
fun logcatTableLogBuffer(systemClock: SystemClock, name: String = "EchoToLogcatTableLogBuffer") =
    TableLogBuffer(maxSize = 50, name, systemClock, logcatEchoTracker = LogcatEchoTrackerAlways())
