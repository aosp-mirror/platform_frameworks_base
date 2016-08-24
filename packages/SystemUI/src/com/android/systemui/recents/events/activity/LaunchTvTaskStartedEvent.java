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

package com.android.systemui.recents.events.activity;

import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.tv.views.TaskCardView;

/**
 * This event is sent following {@link LaunchTvTaskEvent} after the call to the system is made to
 * start the task, only used on TV.
 */
public class LaunchTvTaskStartedEvent extends EventBus.AnimatedEvent {

    public final TaskCardView taskView;

    public LaunchTvTaskStartedEvent(TaskCardView taskView) {
        this.taskView = taskView;
    }

}
