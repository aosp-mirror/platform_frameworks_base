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

package com.android.server.notification;

import android.annotation.NonNull;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

public final class NotificationHistoryFilter {
    private String mPackage;
    private String mChannel;
    private int mNotificationCount;

    private NotificationHistoryFilter() {}

    public String getPackage() {
        return mPackage;
    }

    public String getChannel() {
        return mChannel;
    }

    public int getMaxNotifications() {
        return mNotificationCount;
    }

    /**
     * Returns whether any of the filtering conditions are set
     */
    public boolean isFiltering() {
        return getPackage() != null || getChannel() != null
                || mNotificationCount < Integer.MAX_VALUE;
    }

    /**
     * Returns true if this notification passes the package and channel name filter, false
     * otherwise.
     */
    public boolean matchesPackageAndChannelFilter(HistoricalNotification notification) {
        if (!TextUtils.isEmpty(getPackage())) {
            if (!getPackage().equals(notification.getPackage())) {
                return false;
            } else {
                if (!TextUtils.isEmpty(getChannel())
                        && !getChannel().equals(notification.getChannelId())) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Returns true if the NotificationHistory can accept another notification.
     */
    public boolean matchesCountFilter(NotificationHistory notifications) {
        return notifications.getHistoryCount() < mNotificationCount;
    }

    public static final class Builder {
        private String mPackage = null;
        private String mChannel = null;
        private int mNotificationCount = Integer.MAX_VALUE;

        /**
         * Constructor
         */
        public Builder() {}

        /**
         * Sets a package name filter
         */
        public Builder setPackage(String aPackage) {
            mPackage = aPackage;
            return this;
        }

        /**
         * Sets a channel name filter. Only valid if there is also a package name filter
         */
        public Builder setChannel(String pkg, String channel) {
            setPackage(pkg);
            mChannel = channel;
            return this;
        }

        /**
         * Sets the max historical notifications
         */
        public Builder setMaxNotifications(int notificationCount) {
            mNotificationCount = notificationCount;
            return this;
        }

        /**
         * Makes a NotificationHistoryFilter
         */
        public NotificationHistoryFilter build() {
            NotificationHistoryFilter filter = new NotificationHistoryFilter();
            filter.mPackage = mPackage;
            filter.mChannel = mChannel;
            filter.mNotificationCount = mNotificationCount;
            return filter;
        }
    }
}
