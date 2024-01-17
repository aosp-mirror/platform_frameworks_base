/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import java.util.stream.Stream;

/**
 * A manager which contains notification alerting functionality, providing methods to add and
 * remove notifications that appear on screen for a period of time and dismiss themselves at the
 * appropriate time.  These include heads up notifications and ambient pulses.
 */
public abstract class AlertingNotificationManager {
    private static final String TAG = "AlertNotifManager";

    public AlertingNotificationManager(HeadsUpManagerLogger logger,
            SystemClock systemClock, @Main DelayableExecutor executor) {
    }

    public abstract void showNotification(@NonNull NotificationEntry entry);

    public abstract boolean removeNotification(@NonNull String key, boolean releaseImmediately);

    public abstract void updateNotification(@NonNull String key, boolean alert);

    public abstract void releaseAllImmediately();

    public abstract NotificationEntry getEntry(@NonNull String key);

    @NonNull
    public abstract Stream<NotificationEntry> getAllEntries();

    public abstract boolean hasNotifications();

    public abstract boolean isAlerting(@NonNull String key);

    /**
     * Gets the flag corresponding to the notification content view this alert manager will show.
     *
     * @return flag corresponding to the content view
     */
    public abstract @InflationFlag int getContentFlag();

    protected abstract void addAlertEntry(@NonNull NotificationEntry entry);

    protected abstract void onAlertEntryAdded(@NonNull AlertEntry alertEntry);

    protected abstract void removeAlertEntry(@NonNull String key);


    protected abstract void onAlertEntryRemoved(@NonNull AlertEntry alertEntry);

    /**
     * Returns a new alert entry instance.
     * @return a new AlertEntry
     */
    protected AlertEntry createAlertEntry() {
        return new AlertEntry();
    }

    public abstract boolean canRemoveImmediately(String key);

    public abstract boolean isSticky(String key);

    public abstract long getEarliestRemovalTime(String key);

    protected class AlertEntry implements Comparable<AlertEntry> {

        public void setEntry(@NonNull final NotificationEntry entry) {}

        public void setEntry(@NonNull final NotificationEntry entry,
                @Nullable Runnable removeAlertRunnable) {}

        public void updateEntry(boolean updatePostTime, @Nullable String reason) { }

        public boolean isSticky() {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #isSticky
            return false;
        }

        public boolean isStickyForSomeTime() {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #isStickyForSomeTime
            return false;
        }

        public boolean wasShownLongEnough() {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #wasShownLongEnough
            return false;
        }

        @Override
        public int compareTo(@NonNull AlertEntry alertEntry) {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #compareTo
            return -1;
        }

        public void reset() {}

        public void removeAutoRemovalCallbacks(@Nullable String reason) {}

        public void scheduleAutoRemovalCallback(long delayMillis, @NonNull String reason) {}

        public boolean removeAutoRemovalCallbackInternal() {
            return false;
        }

        public void removeAsSoonAsPossible() {}

        protected long calculatePostTime() {
            return 0;
        }

        protected long calculateFinishTime() {
            // Overridden by HeadsUpManager HeadsUpEntry #calculateFinishTime
            return 0;
        }
    }
}
