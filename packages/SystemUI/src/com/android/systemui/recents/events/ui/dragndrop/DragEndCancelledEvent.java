/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.recents.events.ui.dragndrop;

import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.DropTarget;
import com.android.systemui.recents.views.TaskView;

/**
 * This event is sent whenever a drag end is cancelled because of an error.
 */
public class DragEndCancelledEvent extends EventBus.AnimatedEvent {

    public final TaskStack stack;
    public final Task task;
    public final TaskView taskView;

    public DragEndCancelledEvent(TaskStack stack, Task task, TaskView taskView) {
        this.stack = stack;
        this.task = task;
        this.taskView = taskView;
    }
}
