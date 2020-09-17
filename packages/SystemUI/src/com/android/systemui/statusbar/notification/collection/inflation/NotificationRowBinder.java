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

package com.android.systemui.statusbar.notification.collection.inflation;

import android.annotation.Nullable;

import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;

/**
 * Used by the {@link NotificationEntryManager}. When notifications are added or updated, the binder
 * is asked to (re)inflate and prepare their views. This inflation must occur off the main thread.
 */
public interface NotificationRowBinder {
    /**
     * Called when a notification has been added or updated. The binder must asynchronously inflate
     * and bind the views associated with the notification.
     *
     * TODO: The caller is notified when the inflation completes, but this is currently a very
     * roundabout business. Add an explicit completion/failure callback to this method.
     */
    void inflateViews(
            NotificationEntry entry,
            Runnable onDismissRunnable,
            NotificationRowContentBinder.InflationCallback callback)
            throws InflationException;

    /**
     * Called when the ranking has been updated (but not add or remove has been done). The binder
     * should inspect the old and new adjustments and re-inflate the entry's views if necessary
     * (e.g. if something important changed).
     */
    void onNotificationRankingUpdated(
            NotificationEntry entry,
            @Nullable Integer oldImportance,
            NotificationUiAdjustment oldAdjustment,
            NotificationUiAdjustment newAdjustment,
            NotificationRowContentBinder.InflationCallback callback);
}
