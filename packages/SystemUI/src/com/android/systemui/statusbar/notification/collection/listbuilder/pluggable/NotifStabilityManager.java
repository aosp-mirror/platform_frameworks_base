/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Pluggable for participating in notif stabilization. In particular, suppressing group and
 * section changes.
 *
 * The stability manager should be invalidated when previously suppressed a group or
 * section change is now allowed.
 */
public abstract class NotifStabilityManager extends Pluggable<NotifStabilityManager> {

    protected NotifStabilityManager(String name) {
        super(name);
    }

    /**
     * Called at the beginning of every pipeline run to perform any necessary cleanup from the
     * previous run.
     */
    public abstract void onBeginRun();

    /**
     * Returns whether this notification can currently change groups/parents.
     * Per iteration of the notification pipeline, locally stores this information until the next
     * run of the pipeline. When this method returns true, it's expected that a group change for
     * this entry is being suppressed.
     */
    public abstract boolean isGroupChangeAllowed(NotificationEntry entry);

    /**
     * Returns whether this notification entry can currently change sections.
     * Per iteration of the notification pipeline, locally stores this information until the next
     * run of the pipeline. When this method returns true, it's expected that a section change is
     * being suppressed.
     */
    public abstract boolean isSectionChangeAllowed(NotificationEntry entry);
}
