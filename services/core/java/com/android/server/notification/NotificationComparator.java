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
 * Sorts notifications individually into attention-relevant order.
 */
public class NotificationComparator
        implements Comparator<NotificationRecord> {

    @Override
    public int compare(NotificationRecord left, NotificationRecord right) {
        final int leftImportance = left.getImportance();
        final int rightImportance = right.getImportance();
        if (leftImportance != rightImportance) {
            // by importance, high to low
            return -1 * Integer.compare(leftImportance, rightImportance);
        }

        // Whether or not the notification can bypass DND.
        final int leftPackagePriority = left.getPackagePriority();
        final int rightPackagePriority = right.getPackagePriority();
        if (leftPackagePriority != rightPackagePriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPackagePriority, rightPackagePriority);
        }

        final int leftPriority = left.sbn.getNotification().priority;
        final int rightPriority = right.sbn.getNotification().priority;
        if (leftPriority != rightPriority) {
            // by priority, high to low
            return -1 * Integer.compare(leftPriority, rightPriority);
        }

        final float leftPeople = left.getContactAffinity();
        final float rightPeople = right.getContactAffinity();
        if (leftPeople != rightPeople) {
            // by contact proximity, close to far
            return -1 * Float.compare(leftPeople, rightPeople);
        }

        // then break ties by time, most recent first
        return -1 * Long.compare(left.getRankingTimeMs(), right.getRankingTimeMs());
    }
}
