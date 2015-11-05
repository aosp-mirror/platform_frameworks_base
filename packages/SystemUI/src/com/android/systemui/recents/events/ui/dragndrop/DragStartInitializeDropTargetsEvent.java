/*
 * Copyright (C) 2015 The Android Open Source Project
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
import com.android.systemui.recents.views.RecentsViewTouchHandler;

/**
 * This event is sent by the drag manager when it requires drop targets to register themselves for
 * the current drag gesture.
 */
public class DragStartInitializeDropTargetsEvent extends EventBus.Event {

    public final Task task;
    public final RecentsViewTouchHandler handler;

    public DragStartInitializeDropTargetsEvent(Task task, RecentsViewTouchHandler handler) {
        this.task = task;
        this.handler = handler;
    }
}
