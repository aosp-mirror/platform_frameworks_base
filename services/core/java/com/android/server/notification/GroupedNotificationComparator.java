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

import android.text.TextUtils;
import android.util.Log;

/**
 * Sorts notifications, accounting for groups and sort keys.
 */
public class GroupedNotificationComparator extends NotificationComparator {
    private static final String TAG = "GroupedNotificationComparator";

    @Override
    public int compare(NotificationRecord left, NotificationRecord right) {
        // "recently intrusive" is an ad hoc group that temporarily claims noisy notifications
        if (left.isRecentlyIntrusive() != right.isRecentlyIntrusive()) {
            return left.isRecentlyIntrusive() ? -1 : 1;
        }

        final NotificationRecord leftProxy = left.getRankingProxy();
        if (leftProxy == null) {
            throw new RuntimeException("left proxy cannot be null: " + left.getKey());
        }
        final NotificationRecord rightProxy = right.getRankingProxy();
        if (rightProxy == null) {
            throw new RuntimeException("right proxy cannot be null: " + right.getKey());
        }
        final String leftSortKey = left.getNotification().getSortKey();
        final String rightSortKey = right.getNotification().getSortKey();
        if (leftProxy != rightProxy) {
            // between groups, compare proxies
            return Integer.compare(leftProxy.getAuthoritativeRank(),
                    rightProxy.getAuthoritativeRank());
        } else if (TextUtils.isEmpty(leftSortKey) || TextUtils.isEmpty(rightSortKey)) {
            // missing sort keys, use prior rank
            return Integer.compare(left.getAuthoritativeRank(),
                    right.getAuthoritativeRank());
        } else {
            // use sort keys within group
            return leftSortKey.compareTo(rightSortKey);
        }
    }
}
