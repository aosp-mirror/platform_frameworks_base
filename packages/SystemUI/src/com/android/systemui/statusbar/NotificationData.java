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
import android.widget.ImageView;

import com.android.internal.statusbar.StatusBarNotification;

import java.util.Comparator;
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
        public ImageView largeIcon;
        public Entry() {}
        public Entry(IBinder key, StatusBarNotification n, StatusBarIconView ic) {
            this.key = key;
            this.notification = n;
            this.icon = ic;
        }
    }
    private final ArrayList<Entry> mEntries = new ArrayList<Entry>();
    private final Comparator<Entry> mEntryCmp = new Comparator<Entry>() {
        public int compare(Entry a, Entry b) {
            final StatusBarNotification na = a.notification;
            final StatusBarNotification nb = b.notification;
            int priDiff = na.priority - nb.priority;
            return (priDiff != 0)
                ? priDiff
                : (int)(na.notification.when - nb.notification.when);
        }
    };

    public int size() {
        return mEntries.size();
    }

    public Entry get(int i) {
        return mEntries.get(i);
    }

    public Entry findByKey(IBinder key) {
        for (Entry e : mEntries) {
            if (e.key == key) {
                return e;
            }
        }
        return null;
    }

    public int add(Entry entry) {
        int i;
        int N = mEntries.size();
        for (i=0; i<N; i++) {
            if (mEntryCmp.compare(mEntries.get(i), entry) > 0) {
                break;
            }
        }
        mEntries.add(i, entry);
        return i;
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
        entry.largeIcon = null; // TODO add support for large icons
        return add(entry);
    }

    public Entry remove(IBinder key) {
        Entry e = findByKey(key);
        if (e != null) {
            mEntries.remove(e);
        }
        return e;
    }

    /**
     * Return whether there are any visible items (i.e. items without an error).
     */
    public boolean hasVisibleItems() {
        for (Entry e : mEntries) {
            if (e.expanded != null) { // the view successfully inflated
                return true;
            }
        }
        return false;
    }

    /**
     * Return whether there are any clearable items (that aren't errors).
     */
    public boolean hasClearableItems() {
        for (Entry e : mEntries) {
            if (e.expanded != null) { // the view successfully inflated
                if (e.notification.isClearable()) {
                    return true;
                }
            }
        }
        return false;
    }
}
