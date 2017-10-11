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

import android.util.Slog;

import java.util.Comparator;

/**
 * Sorts notifications by their global sort key.
 */
public class GlobalSortKeyComparator implements Comparator<NotificationRecord> {
    private final static String TAG = "GlobalSortComp";

    @Override
    public int compare(NotificationRecord left, NotificationRecord right) {
        if (left.getGlobalSortKey() == null) {
            Slog.wtf(TAG, "Missing left global sort key: " + left);
            return 1;
        }
        if (right.getGlobalSortKey() == null) {
            Slog.wtf(TAG, "Missing right global sort key: " + right);
            return  -1;
        }
        return left.getGlobalSortKey().compareTo(right.getGlobalSortKey());
    }
}
