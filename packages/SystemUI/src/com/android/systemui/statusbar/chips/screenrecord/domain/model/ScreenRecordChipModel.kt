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

package com.android.systemui.statusbar.chips.screenrecord.domain.model

import android.app.ActivityManager

/** Represents the status of screen record needed to show a chip in the status bar. */
sealed interface ScreenRecordChipModel {
    /** There's nothing related to screen recording happening. */
    data object DoingNothing : ScreenRecordChipModel

    /** A screen recording will begin in [millisUntilStarted] ms. */
    data class Starting(val millisUntilStarted: Long) : ScreenRecordChipModel

    /**
     * There's an active screen recording happening.
     *
     * @property recordedTask the task being recorded if the user is recording only a single app.
     *   Null if the user is recording the entire screen or we don't have the task info yet.
     */
    data class Recording(
        val recordedTask: ActivityManager.RunningTaskInfo?,
    ) : ScreenRecordChipModel
}
