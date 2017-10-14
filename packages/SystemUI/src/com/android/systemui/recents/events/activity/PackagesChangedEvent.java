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
import com.android.systemui.recents.views.TaskStackView;
import com.android.systemui.recents.RecentsActivity;

/**
 * This event is sent by {@link RecentsActivity} when a package on the the system changes.
 * {@link TaskStackView}s listen for this event, and remove the tasks associated with the removed
 * packages.
 */
public class PackagesChangedEvent extends EventBus.Event {

    public final String packageName;
    public final int userId;

    public PackagesChangedEvent(String packageName, int userId) {
        this.packageName = packageName;
        this.userId = userId;
    }
}
