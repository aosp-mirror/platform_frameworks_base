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

package com.android.systemui.screenshot.data.model

import android.content.ComponentName
import android.graphics.Rect

/** A child task within a RootTaskInfo */
data class ChildTaskModel(
    /** The task identifier */
    val id: Int,
    /** The task name */
    val name: String,
    /** The location and size of the task */
    val bounds: Rect,
    /** The user which created the task. */
    val userId: Int,
) {
    val componentName: ComponentName?
        get() = ComponentName.unflattenFromString(name)
}
