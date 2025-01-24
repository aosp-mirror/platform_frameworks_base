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

package com.android.wm.shell.automotive

import android.app.ActivityManager
import android.graphics.Rect
import android.view.SurfaceControl

/**
 * Represents an auto task stack, which is always in multi-window mode.
 *
 * @property id The ID of the task stack.
 * @property displayId The ID of the display the task stack is on.
 * @property leash The surface control leash of the task stack.
 */
interface AutoTaskStack {
    val id: Int
    val displayId: Int
    var leash: SurfaceControl
}

/**
 * Data class representing the state of an auto task stack.
 *
 * @property bounds The bounds of the task stack.
 * @property childrenTasksVisible Whether the child tasks of the stack are visible.
 * @property layer The layer of the task stack.
 */
data class AutoTaskStackState(
    val bounds: Rect = Rect(),
    val childrenTasksVisible: Boolean,
    val layer: Int
)

/**
 * Data class representing a root task stack.
 *
 * @property id The ID of the root task stack
 * @property displayId The ID of the display the root task stack is on.
 * @property leash The surface control leash of the root task stack.
 * @property rootTaskInfo The running task info of the root task.
 */
data class RootTaskStack(
    override val id: Int,
    override val displayId: Int,
    override var leash: SurfaceControl,
    var rootTaskInfo: ActivityManager.RunningTaskInfo
) : AutoTaskStack
