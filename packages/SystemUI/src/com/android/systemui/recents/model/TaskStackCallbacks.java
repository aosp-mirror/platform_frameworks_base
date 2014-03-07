/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.model;

/* Task stack callbacks */
public interface TaskStackCallbacks {
    /* Notifies when a task has been added to the stack */
    public void onStackTaskAdded(TaskStack stack, Task t);
    /* Notifies when a task has been removed from the stack */
    public void onStackTaskRemoved(TaskStack stack, Task t);
    /** Notifies when the stack was filtered */
    public void onStackFiltered(TaskStack stack);
    /** Notifies when the stack was un-filtered */
    public void onStackUnfiltered(TaskStack stack);
}