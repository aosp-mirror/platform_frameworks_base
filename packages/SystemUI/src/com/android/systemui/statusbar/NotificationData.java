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

package com.android.systemui.statusbar;

import android.app.Notification;
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
        public View row; // the outer expanded view
        public View content; // takes the click events and sends the PendingIntent
        public View expanded; // the inflated RemoteViews
    }
    private final ArrayList<Entry> mEntries = new ArrayList<Entry>();

    public int size() {
        return mEntries.size();
    }

    public Entry getEntryAt(int index) {
        return mEntries.get(index);
    }

    public int findEntry(IBinder key) {
        final int N = mEntries.size();
        for (int i=0; i<N; i++) {
            Entry entry = mEntries.get(i);
            if (entry.key == key) {
                return i;
            }
        }
        return -1;
    }

    public int add(IBinder key, StatusBarNotification notification, View row, View content,
            View expanded, StatusBarIconView icon) {
        Entry entry = new Entry();
        entry.key = key;
        entry.notification = notification;
        entry.row = row;
        entry.content = content;
        entry.expanded = expanded;
        entry.icon = icon;
        final int index = chooseIndex(notification.notification.when);
        mEntries.add(index, entry);
        return index;
    }

    public Entry remove(IBinder key) {
        final int N = mEntries.size();
        for (int i=0; i<N; i++) {
            Entry entry = mEntries.get(i);
            if (entry.key == key) {
                mEntries.remove(i);
                return entry;
            }
        }
        return null;
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

    /**
     * Return whether there are any visible items (i.e. items without an error).
     */
    public boolean hasVisibleItems() {
        final int N = mEntries.size();
        for (int i=0; i<N; i++) {
            Entry entry = mEntries.get(i);
            if (entry.expanded != null) { // the view successfully inflated
                return true;
            }
        }
        return false;
    }

    /**
     * Return whether there are any clearable items (that aren't errors).
     */
    public boolean hasClearableItems() {
        final int N = mEntries.size();
        for (int i=0; i<N; i++) {
            Entry entry = mEntries.get(i);
            if (entry.expanded != null) { // the view successfully inflated
                if ((entry.notification.notification.flags & Notification.FLAG_NO_CLEAR) == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
