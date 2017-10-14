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
import com.android.systemui.shared.recents.model.TaskStack;

/**
 * This is sent by the activity whenever the multi-window state has changed.
 */
public class MultiWindowStateChangedEvent extends EventBus.AnimatedEvent {

    public final boolean inMultiWindow;
    // This flag is only used when undocking a task
    public final boolean showDeferredAnimation;
    public final TaskStack stack;

    public MultiWindowStateChangedEvent(boolean inMultiWindow, boolean showDeferredAnimation,
            TaskStack stack) {
        this.inMultiWindow = inMultiWindow;
        this.showDeferredAnimation = showDeferredAnimation;
        this.stack = stack;
    }
}
