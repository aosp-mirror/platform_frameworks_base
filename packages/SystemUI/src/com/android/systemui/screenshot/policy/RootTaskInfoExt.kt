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

package com.android.systemui.screenshot.policy

import android.app.ActivityTaskManager.RootTaskInfo
import com.android.systemui.screenshot.data.model.ChildTaskModel

/** The child tasks of A RootTaskInfo as [ChildTaskModel] in top-down (z-index ascending) order. */
internal fun RootTaskInfo.childTasksTopDown(): Sequence<ChildTaskModel> {
    return ((childTaskIds.size - 1) downTo 0).asSequence().map { index ->
        ChildTaskModel(
            childTaskIds[index],
            childTaskNames[index],
            childTaskBounds[index],
            childTaskUserIds[index]
        )
    }
}

internal fun RootTaskInfo.hasChildTasks() = childTaskUserIds.isNotEmpty()
