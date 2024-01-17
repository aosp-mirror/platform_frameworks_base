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
    protected final SystemClock mSystemClock;
    protected final ArrayMap<String, AlertEntry> mAlertEntries = new ArrayMap<>();
    protected final HeadsUpManagerLogger mLogger;
    protected int mMinimumDisplayTime;
    protected int mStickyForSomeTimeAutoDismissTime;
    protected int mAutoDismissTime;
    private DelayableExecutor mExecutor;

    public AlertingNotificationManager(HeadsUpManagerLogger logger,
            SystemClock systemClock, @Main DelayableExecutor executor) {
        mLogger = logger;
        mExecutor = executor;
        mSystemClock = systemClock;
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
        @Nullable public NotificationEntry mEntry;
        public long mPostTime;
        public long mEarliestRemovalTime;

        @Nullable protected Runnable mRemoveAlertRunnable;
        @Nullable private Runnable mCancelRemoveAlertRunnable;

        public void setEntry(@NonNull final NotificationEntry entry) {
            setEntry(entry, () -> removeAlertEntry(entry.getKey()));
        }

        public void setEntry(@NonNull final NotificationEntry entry,
                @Nullable Runnable removeAlertRunnable) {
            mEntry = entry;
            mRemoveAlertRunnable = removeAlertRunnable;

            mPostTime = calculatePostTime();
            updateEntry(true /* updatePostTime */, "setEntry");
        }

        /**
         * Updates an entry's removal time.
         * @param updatePostTime whether or not to refresh the post time
         */
        public void updateEntry(boolean updatePostTime, @Nullable String reason) {
            mLogger.logUpdateEntry(mEntry, updatePostTime, reason);

            final long now = mSystemClock.elapsedRealtime();
            mEarliestRemovalTime = now + mMinimumDisplayTime;

            if (updatePostTime) {
                mPostTime = Math.max(mPostTime, now);
            }

            if (isSticky()) {
                removeAutoRemovalCallbacks("updateEntry (sticky)");
                return;
            }

            final long finishTime = calculateFinishTime();
            final long timeLeft = Math.max(finishTime - now, mMinimumDisplayTime);
            scheduleAutoRemovalCallback(timeLeft, "updateEntry (not sticky)");
        }

        /**
         * Whether or not the notification is "sticky" i.e. should stay on screen regardless
         * of the timer (forever) and should be removed externally.
         * @return true if the notification is sticky
         */
        public boolean isSticky() {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #isSticky
            return false;
        }

        public boolean isStickyForSomeTime() {
            // This implementation is overridden by HeadsUpManager HeadsUpEntry #isStickyForSomeTime
            return false;
        }

        /**
         * Whether the notification has befen on screen long enough and can be removed.
         * @return true if the notification has been on screen long enough
         */
        public boolean wasShownLongEnough() {
            return mEarliestRemovalTime < mSystemClock.elapsedRealtime();
        }

        @Override
        public int compareTo(@NonNull AlertEntry alertEntry) {
            return (mPostTime < alertEntry.mPostTime)
                    ? 1 : ((mPostTime == alertEntry.mPostTime)
                            ? mEntry.getKey().compareTo(alertEntry.mEntry.getKey()) : -1);
        }

        public void reset() {
            removeAutoRemovalCallbacks("reset()");
            mEntry = null;
            mRemoveAlertRunnable = null;
        }

        /**
         * Clear any pending removal runnables.
         */
        public void removeAutoRemovalCallbacks(@Nullable String reason) {
            final boolean removed = removeAutoRemovalCallbackInternal();

            if (removed) {
                mLogger.logAutoRemoveCanceled(mEntry, reason);
            }
        }

        private void scheduleAutoRemovalCallback(long delayMillis, @NonNull String reason) {
            if (mRemoveAlertRunnable == null) {
                Log.wtf(TAG, "scheduleAutoRemovalCallback with no callback set");
                return;
            }

            final boolean removed = removeAutoRemovalCallbackInternal();

            if (removed) {
                mLogger.logAutoRemoveRescheduled(mEntry, delayMillis, reason);
            } else {
                mLogger.logAutoRemoveScheduled(mEntry, delayMillis, reason);
            }


            mCancelRemoveAlertRunnable = mExecutor.executeDelayed(mRemoveAlertRunnable,
                    delayMillis);
        }

        private boolean removeAutoRemovalCallbackInternal() {
            final boolean scheduled = (mCancelRemoveAlertRunnable != null);

            if (scheduled) {
                mCancelRemoveAlertRunnable.run();
                mCancelRemoveAlertRunnable = null;
            }

            return scheduled;
        }

        /**
         * Remove the alert at the earliest allowed removal time.
         */
        public void removeAsSoonAsPossible() {
            if (mRemoveAlertRunnable != null) {
                final long timeLeft = mEarliestRemovalTime - mSystemClock.elapsedRealtime();
                scheduleAutoRemovalCallback(timeLeft, "removeAsSoonAsPossible");
            }
        }

        /**
         * Calculate what the post time of a notification is at some current time.
         * @return the post time
         */
        protected long calculatePostTime() {
            return mSystemClock.elapsedRealtime();
        }

        /**
         * @return When the notification should auto-dismiss itself, based on
         * {@link SystemClock#elapsedRealtime()}
         */
        protected long calculateFinishTime() {
            // Overridden by HeadsUpManager HeadsUpEntry #calculateFinishTime
            return 0;
        }
    }
}
