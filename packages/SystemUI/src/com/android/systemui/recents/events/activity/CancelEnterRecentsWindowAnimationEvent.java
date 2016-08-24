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

package com.android.systemui.recents.events.activity;

import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.model.Task;

/**
 * This is sent when we want to cancel the enter-recents window animation for the launch task.
 */
public class CancelEnterRecentsWindowAnimationEvent extends EventBus.Event {

    // This is set for the task that is launching, which allows us to ensure that we are not
    // cancelling the same task animation (it will just be overwritten instead)
    public final Task launchTask;

    public CancelEnterRecentsWindowAnimationEvent(Task launchTask) {
        this.launchTask = launchTask;
    }
}
