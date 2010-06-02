/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.policy.statusbar.phone;

import android.os.IBinder;
import android.view.View;

import com.android.internal.statusbar.StatusBarNotification;

import java.util.ArrayList;

/**
 * The list of currently displaying notifications.
 */
public class NotificationData {
    public static final class Entry {
        public IBinder key;
        public StatusBarNotification notification;
        public StatusBarIconView icon;
        public View expanded;
    }
    private final ArrayList<Entry> mEntries = new ArrayList<Entry>();

    public int size() {
        return mEntries.size();
    }

    public Entry getEntryAt(int index) {
        return mEntries.get(index);
    }

    public int add(IBinder key, StatusBarNotification notification, View expanded) {
        Entry entry = new Entry();
        entry.key = key;
        entry.notification = notification;
        entry.expanded = expanded;
        final int index = chooseIndex(notification.notification.when);
        mEntries.add(index, entry);
        return index;
    }

    private int chooseIndex(final long when) {
        final int N = mEntries.size();
        for (int i=0; i<N; i++) {
            Entry entry = mEntries.get(i);
            if (entry.notification.notification.when > when) {
                return i;
            }
        }
        return N;
    }
}
