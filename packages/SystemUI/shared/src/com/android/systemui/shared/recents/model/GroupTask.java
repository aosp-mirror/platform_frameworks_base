/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shared.recents.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A group task in the recent tasks list.
 * TODO: Move this into Launcher
 */
public class GroupTask {
    public @NonNull Task task1;
    public @Nullable Task task2;

    public GroupTask(@NonNull Task t1, @Nullable Task t2) {
        task1 = t1;
        task2 = t2;
    }

    public GroupTask(@NonNull GroupTask group) {
        task1 = new Task(group.task1);
        task2 = group.task2 != null
                ? new Task(group.task2)
                : null;
    }

    public boolean containsTask(int taskId) {
        return task1.key.id == taskId || (task2 != null && task2.key.id == taskId);
    }
}
