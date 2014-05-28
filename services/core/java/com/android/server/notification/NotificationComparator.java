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

import java.util.Comparator;

/**
 * Sorts notificaitons into attention-relelvant order.
 */
public class NotificationComparator
        implements Comparator<NotificationRecord> {

    @Override
    public int compare(NotificationRecord lhs, NotificationRecord rhs) {
        if (lhs.isRecentlyIntrusive() != rhs.isRecentlyIntrusive()) {
            return lhs.isRecentlyIntrusive() ? -1 : 1;
        }
        final int leftScore = lhs.sbn.getScore();
        final int rightScore = rhs.sbn.getScore();
        if (leftScore != rightScore) {
            // by priority, high to low
            return -1 * Integer.compare(leftScore, rightScore);
        }
        final float leftPeple = lhs.getContactAffinity();
        final float rightPeople = rhs.getContactAffinity();
        if (leftPeple != rightPeople) {
            // by contact proximity, close to far
            return -1 * Float.compare(leftPeple, rightPeople);
        }
        // then break ties by time, most recent first
        return -1 * Long.compare(lhs.sbn.getPostTime(), rhs.sbn.getPostTime());
    }
}
