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
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The list of currently displaying notifications.
 *
 * TODO: Rename to NotificationList.
 */
public class NotificationData {
    public static final class Entry {
        public String key;
        public StatusBarNotification notification;
        public StatusBarIconView icon;
        public ExpandableNotificationRow row; // the outer expanded view
        public View expanded; // the inflated RemoteViews
        public View expandedPublic; // for insecure lockscreens
        public View expandedBig;
        private boolean interruption;
        public Entry() {}
        public Entry(StatusBarNotification n, StatusBarIconView ic) {
            this.key = n.getKey();
            this.notification = n;
            this.icon = ic;
        }
        public void setBigContentView(View bigContentView) {
            this.expandedBig = bigContentView;
            row.setExpandable(bigContentView != null);
        }
        public View getBigContentView() {
            return expandedBig;
        }
        public View getPublicContentView() { return expandedPublic; }
        /**
         * Set the flag indicating that this is being touched by the user.
         */
        public void setUserLocked(boolean userLocked) {
            row.setUserLocked(userLocked);
        }

        public void setInterruption() {
            interruption = true;
        }
    }

    private final ArrayList<Entry> mEntries = new ArrayList<Entry>();
    private Ranking mRanking;
    private final Comparator<Entry> mRankingComparator = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            if (mRanking != null) {
                return mRanking.getRank(a.key) - mRanking.getRank(b.key);
            }

            final StatusBarNotification na = a.notification;
            final StatusBarNotification nb = b.notification;
            int d = nb.getScore() - na.getScore();
            if (a.interruption != b.interruption) {
                return a.interruption ? -1 : 1;
            } else if (d != 0) {
                return d;
            } else {
                return (int) (nb.getNotification().when - na.getNotification().when);
            }
        }
    };

    public int size() {
        return mEntries.size();
    }

    public Entry get(int i) {
        return mEntries.get(i);
    }

    public Entry findByKey(String key) {
        for (Entry e : mEntries) {
            if (e.key.equals(key)) {
                return e;
            }
        }
        return null;
    }

    public void add(Entry entry, Ranking ranking) {
        mEntries.add(entry);
        updateRankingAndSort(ranking);
    }

    public Entry remove(String key, Ranking ranking) {
        Entry e = findByKey(key);
        if (e == null) {
            return null;
        }
        mEntries.remove(e);
        updateRankingAndSort(ranking);
        return e;
    }

    public void updateRanking(Ranking ranking) {
        updateRankingAndSort(ranking);
    }

    public boolean isAmbient(String key) {
        // TODO: Remove when switching to NotificationListener.
        if (mRanking == null) {
            for (Entry entry : mEntries) {
                if (key.equals(entry.key)) {
                    return entry.notification.getNotification().priority ==
                            Notification.PRIORITY_MIN;
                }
            }
        } else {
            return mRanking.isAmbient(key);
        }
        return false;
    }

    private void updateRankingAndSort(Ranking ranking) {
        if (ranking != null) {
            mRanking = ranking;
        }
        Collections.sort(mEntries, mRankingComparator);
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
