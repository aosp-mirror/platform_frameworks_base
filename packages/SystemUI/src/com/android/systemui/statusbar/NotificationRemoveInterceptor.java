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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.notification.NotificationListenerService;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Interface for anything that may need to prevent notifications from being removed. This is
 * similar to a {@link NotificationLifetimeExtender} in the sense that it extends the life of
 * a notification by preventing the removal, however, unlike the extender, the remove interceptor
 * gets first pick at intercepting any type of removal -- the life time extender is unable to
 * extend the life of a user dismissed or force removed notification.
 */
public interface NotificationRemoveInterceptor {

    /**
     * Called when a notification has been removed.
     *
     * @param key the key of the notification being removed. Never null
     * @param entry the entry of the notification being removed.
     * @param removeReason why the notification is being removed, e.g.
     * {@link NotificationListenerService#REASON_CANCEL} or 0 if unknown.
     *
     * @return true if the removal should be ignored, false otherwise.
     */
    boolean onNotificationRemoveRequested(
            @NonNull String key,
            @Nullable NotificationEntry entry,
            int removeReason);
}
