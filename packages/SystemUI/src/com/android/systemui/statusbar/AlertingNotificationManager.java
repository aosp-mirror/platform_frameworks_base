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
import android.util.ArraySet;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

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

    /**
     * Called when posting a new notification that should alert the user and appear on screen.
     * Adds the notification to be managed.
     * @param entry entry to show
     */
    public void showNotification(@NonNull NotificationEntry entry) {
        mLogger.logShowNotification(entry);
        addAlertEntry(entry);
        updateNotification(entry.getKey(), true /* alert */);
        entry.setInterruption();
    }

    /**
     * Try to remove the notification.  May not succeed if the notification has not been shown long
     * enough and needs to be kept around.
     * @param key the key of the notification to remove
     * @param releaseImmediately force a remove regardless of earliest removal time
     * @return true if notification is removed, false otherwise
     */
    public boolean removeNotification(@NonNull String key, boolean releaseImmediately) {
        mLogger.logRemoveNotification(key, releaseImmediately);
        AlertEntry alertEntry = mAlertEntries.get(key);
        if (alertEntry == null) {
            return true;
        }
        if (releaseImmediately || canRemoveImmediately(key)) {
            removeAlertEntry(key);
        } else {
            alertEntry.removeAsSoonAsPossible();
            return false;
        }
        return true;
    }

    /**
     * Called when the notification state has been updated.
     * @param key the key of the entry that was updated
     * @param alert whether the notification should alert again and force reevaluation of
     *              removal time
     */
    public void updateNotification(@NonNull String key, boolean alert) {
        AlertEntry alertEntry = mAlertEntries.get(key);
        mLogger.logUpdateNotification(key, alert, alertEntry != null);
        if (alertEntry == null) {
            // the entry was released before this update (i.e by a listener) This can happen
            // with the groupmanager
            return;
        }

        alertEntry.mEntry.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        if (alert) {
            alertEntry.updateEntry(true /* updatePostTime */, "updateNotification");
        }
    }

    /**
     * Clears all managed notifications.
     */
    public void releaseAllImmediately() {
        mLogger.logReleaseAllImmediately();
        // A copy is necessary here as we are changing the underlying map.  This would cause
        // undefined behavior if we iterated over the key set directly.
        ArraySet<String> keysToRemove = new ArraySet<>(mAlertEntries.keySet());
        for (String key : keysToRemove) {
            removeAlertEntry(key);
        }
    }

    /**
     * Returns the entry if it is managed by this manager.
     * @param key key of notification
     * @return the entry
     */
    @Nullable
    public NotificationEntry getEntry(@NonNull String key) {
        AlertEntry entry = mAlertEntries.get(key);
        return entry != null ? entry.mEntry : null;
    }

    /**
     * Returns the stream of all current notifications managed by this manager.
     * @return all entries
     */
    @NonNull
    public Stream<NotificationEntry> getAllEntries() {
        return mAlertEntries.values().stream().map(headsUpEntry -> headsUpEntry.mEntry);
    }

    /**
     * Whether or not there are any active alerting notifications.
     * @return true if there is an alert, false otherwise
     */
    public boolean hasNotifications() {
        return !mAlertEntries.isEmpty();
    }

    /**
     * Whether or not the given notification is alerting and managed by this manager.
     * @return true if the notification is alerting
     */
    public boolean isAlerting(@NonNull String key) {
        return mAlertEntries.containsKey(key);
    }

    /**
     * Gets the flag corresponding to the notification content view this alert manager will show.
     *
     * @return flag corresponding to the content view
     */
    public abstract @InflationFlag int getContentFlag();

    /**
     * Add a new entry and begin managing it.
     * @param entry the entry to add
     */
    protected final void addAlertEntry(@NonNull NotificationEntry entry) {
        AlertEntry alertEntry = createAlertEntry();
        alertEntry.setEntry(entry);
        mAlertEntries.put(entry.getKey(), alertEntry);
        onAlertEntryAdded(alertEntry);
        entry.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.setIsAlerting(true);
    }

    /**
     * Manager-specific logic that should occur when an entry is added.
     * @param alertEntry alert entry added
     */
    protected abstract void onAlertEntryAdded(@NonNull AlertEntry alertEntry);

    /**
     * Remove a notification and reset the alert entry.
     * @param key key of notification to remove
     */
    protected final void removeAlertEntry(@NonNull String key) {
        AlertEntry alertEntry = mAlertEntries.get(key);
        if (alertEntry == null) {
            return;
        }
        NotificationEntry entry = alertEntry.mEntry;

        // If the notification is animating, we will remove it at the end of the animation.
        if (entry != null && entry.isExpandAnimationRunning()) {
            return;
        }
        entry.demoteStickyHun();
        mAlertEntries.remove(key);
        onAlertEntryRemoved(alertEntry);
        entry.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        alertEntry.reset();
    }

    /**
     * Manager-specific logic that should occur when an alert entry is removed.
     * @param alertEntry alert entry removed
     */
    protected abstract void onAlertEntryRemoved(@NonNull AlertEntry alertEntry);

    /**
     * Returns a new alert entry instance.
     * @return a new AlertEntry
     */
    protected AlertEntry createAlertEntry() {
        return new AlertEntry();
    }

    /**
     * Whether or not the alert can be removed currently.  If it hasn't been on screen long enough
     * it should not be removed unless forced
     * @param key the key to check if removable
     * @return true if the alert entry can be removed
     */
    public boolean canRemoveImmediately(String key) {
        AlertEntry alertEntry = mAlertEntries.get(key);
        return alertEntry == null || alertEntry.wasShownLongEnough()
                || alertEntry.mEntry.isRowDismissed();
    }

    /**
     * @param key
     * @return true if the entry is (pinned and expanded) or (has an active remote input)
     */
    public boolean isSticky(String key) {
        AlertEntry alerting = mAlertEntries.get(key);
        if (alerting != null) {
            return alerting.isSticky();
        }
        return false;
    }

    /**
     * @param key
     * @return When a HUN entry should be removed in milliseconds from now
     */
    public long getEarliestRemovalTime(String key) {
        AlertEntry alerting = mAlertEntries.get(key);
        if (alerting != null) {
            return Math.max(0, alerting.mEarliestRemovalTime - mSystemClock.elapsedRealtime());
        }
        return 0;
    }

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
