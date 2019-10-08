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

package com.android.systemui.recents.events.component;

import android.content.Context;

import com.android.systemui.recents.events.EventBus;

/**
 * This is sent when we want to start screen pinning.
 */
public class ScreenPinningRequestEvent extends EventBus.Event {

    public final Context applicationContext;
    public final int taskId;

    public ScreenPinningRequestEvent(Context context, int taskId) {
        this.applicationContext = context.getApplicationContext();
        this.taskId = taskId;
    }
}
