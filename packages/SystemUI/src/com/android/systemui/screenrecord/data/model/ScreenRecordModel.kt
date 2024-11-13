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

package com.android.systemui.screenrecord.data.model

/** Data model for the current state of screen recording. */
sealed interface ScreenRecordModel {
    /** There's an active screen recording happening. */
    data object Recording : ScreenRecordModel

    /** A screen recording will begin in [millisUntilStarted] ms. */
    data class Starting(val millisUntilStarted: Long) : ScreenRecordModel {
        val countdownSeconds = millisUntilStarted.toCountdownSeconds()

        companion object {
            /**
             * Returns the number of seconds until screen recording will start, used to show a 3-2-1
             * countdown.
             */
            fun Long.toCountdownSeconds() = Math.floorDiv(this + 500, 1000)
        }
    }

    /** There's nothing related to screen recording happening. */
    data object DoingNothing : ScreenRecordModel
}
