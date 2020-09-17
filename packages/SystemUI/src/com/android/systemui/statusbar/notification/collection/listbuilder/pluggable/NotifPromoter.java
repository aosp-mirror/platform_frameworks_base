/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 *  Pluggable for participating in notif promotion. Notif promoters can upgrade notifications
 *  from being children of a group to top-level notifications. See
 *  {@link NotifPipeline#addPromoter}.
 */
public abstract class NotifPromoter extends Pluggable<NotifPromoter> {
    protected NotifPromoter(String name) {
        super(name);
    }

    /**
     * If true, the child will be removed from its parent and placed at the top level of the notif
     * list. By the time this method is called, child.getParent() has been set, so you can
     * examine it (or any other entries in the notif list) for extra information.
     *
     * This method is only called on notifs that are currently children of groups. This doesn't
     * necessarily mean that your promoter will get called on every child notification, however. If
     * another promoter returns true before yours, we'll skip straight to the next notif.
     */
    public abstract boolean shouldPromoteToTopLevel(NotificationEntry child);
}
