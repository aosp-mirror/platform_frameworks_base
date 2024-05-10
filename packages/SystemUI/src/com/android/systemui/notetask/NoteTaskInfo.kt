/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.notetask

import android.os.UserHandle
import com.android.systemui.notetask.NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE

/** Contextual information required to launch a Note Task by [NoteTaskController]. */
data class NoteTaskInfo(
    val packageName: String,
    val uid: Int,
    val user: UserHandle,
    val entryPoint: NoteTaskEntryPoint? = null,
    val isKeyguardLocked: Boolean = false,
) {

    val launchMode: NoteTaskLaunchMode =
        if (isKeyguardLocked || entryPoint == WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE) {
            NoteTaskLaunchMode.Activity
        } else {
            NoteTaskLaunchMode.AppBubble
        }
}
