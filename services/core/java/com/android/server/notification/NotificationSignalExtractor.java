/*
* Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.notification;

import android.content.Context;

/**
 * Extracts signals that will be useful to the {@link NotificationComparator} and caches them
 *  on the {@link NotificationRecord} object. These annotations will
 *  not be passed on to {@link android.service.notification.NotificationListenerService}s.
 */
public interface NotificationSignalExtractor {

    /** One-time initialization. */
    public void initialize(Context context, NotificationUsageStats usageStats);

    /**
     * Called once per notification that is posted or updated.
     *
     * @return null if the work is done, or a future if there is more to do. The
     * {@link RankingReconsideration} will be run on a worker thread, and if notifications
     * are re-ordered by that execution, the {@link NotificationManagerService} may send order
     * update events to the {@link android.service.notification.NotificationListenerService}s.
     */
    public RankingReconsideration process(NotificationRecord notification);

    /**
     * Called whenever the {@link RankingConfig} changes.
     *
     * @param config information about which signals are important.
     */
    void setConfig(RankingConfig config);
}
