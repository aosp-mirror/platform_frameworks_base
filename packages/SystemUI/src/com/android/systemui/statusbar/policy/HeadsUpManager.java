/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;

/**
 * A manager which handles heads up notifications which is a special mode where
 * they simply peek from the top of the screen.
 */
public abstract class HeadsUpManager extends AlertingNotificationManager {
    private static final String TAG = "HeadsUpManager";
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";

    protected final HashSet<OnHeadsUpChangedListener> mListeners = new HashSet<>();

    protected final Context mContext;

    protected int mTouchAcceptanceDelay;
    protected int mSnoozeLengthMs;
    protected boolean mHasPinnedNotification;
    protected int mUser;

    private final ArrayMap<String, Long> mSnoozedPackages;
    private final AccessibilityManagerWrapper mAccessibilityMgr;

    public HeadsUpManager(@NonNull final Context context) {
        mContext = context;
        mAccessibilityMgr = Dependency.get(AccessibilityManagerWrapper.class);
        Resources resources = context.getResources();
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mAutoDismissNotificationDecay = resources.getInteger(R.integer.heads_up_notification_decay);
        mTouchAcceptanceDelay = resources.getInteger(R.integer.touch_acceptance_delay);
        mSnoozedPackages = new ArrayMap<>();
        int defaultSnoozeLengthMs =
                resources.getInteger(R.integer.heads_up_default_snooze_length_ms);

        mSnoozeLengthMs = Settings.Global.getInt(context.getContentResolver(),
                SETTING_HEADS_UP_SNOOZE_LENGTH_MS, defaultSnoozeLengthMs);
        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final int packageSnoozeLengthMs = Settings.Global.getInt(
                        context.getContentResolver(), SETTING_HEADS_UP_SNOOZE_LENGTH_MS, -1);
                if (packageSnoozeLengthMs > -1 && packageSnoozeLengthMs != mSnoozeLengthMs) {
                    mSnoozeLengthMs = packageSnoozeLengthMs;
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);
                    }
                }
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_HEADS_UP_SNOOZE_LENGTH_MS), false,
                settingsObserver);
    }

    /**
     * Adds an OnHeadUpChangedListener to observe events.
     */
    public void addListener(@NonNull OnHeadsUpChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes the OnHeadUpChangedListener from the observer list.
     */
    public void removeListener(@NonNull OnHeadsUpChangedListener listener) {
        mListeners.remove(listener);
    }

    public void updateNotification(@NonNull String key, boolean alert) {
        super.updateNotification(key, alert);
        AlertEntry alertEntry = getHeadsUpEntry(key);
        if (alert && alertEntry != null) {
            setEntryPinned((HeadsUpEntry) alertEntry, shouldHeadsUpBecomePinned(alertEntry.mEntry));
        }
    }

    protected boolean shouldHeadsUpBecomePinned(@NonNull NotificationEntry entry) {
        return hasFullScreenIntent(entry);
    }

    protected boolean hasFullScreenIntent(@NonNull NotificationEntry entry) {
        return entry.notification.getNotification().fullScreenIntent != null;
    }

    protected void setEntryPinned(
            @NonNull HeadsUpManager.HeadsUpEntry headsUpEntry, boolean isPinned) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "setEntryPinned: " + isPinned);
        }
        NotificationEntry entry = headsUpEntry.mEntry;
        if (entry.isRowPinned() != isPinned) {
            entry.setRowPinned(isPinned);
            updatePinnedMode();
            for (OnHeadsUpChangedListener listener : mListeners) {
                if (isPinned) {
                    listener.onHeadsUpPinned(entry);
                } else {
                    listener.onHeadsUpUnPinned(entry);
                }
            }
        }
    }

    public @InflationFlag int getContentFlag() {
        return FLAG_CONTENT_VIEW_HEADS_UP;
    }

    @Override
    protected void onAlertEntryAdded(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setHeadsUp(true);
        setEntryPinned((HeadsUpEntry) alertEntry, shouldHeadsUpBecomePinned(entry));
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, true);
        }
    }

    @Override
    protected void onAlertEntryRemoved(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setHeadsUp(false);
        setEntryPinned((HeadsUpEntry) alertEntry, false /* isPinned */);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, false);
        }
        entry.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_HEADS_UP);
    }

    protected void updatePinnedMode() {
        boolean hasPinnedNotification = hasPinnedNotificationInternal();
        if (hasPinnedNotification == mHasPinnedNotification) {
            return;
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Pinned mode changed: " + mHasPinnedNotification + " -> " +
                       hasPinnedNotification);
        }
        mHasPinnedNotification = hasPinnedNotification;
        if (mHasPinnedNotification) {
            MetricsLogger.count(mContext, "note_peek", 1);
        }
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpPinnedModeChanged(hasPinnedNotification);
        }
    }

    /**
     * Returns if the given notification is snoozed or not.
     */
    public boolean isSnoozed(@NonNull String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > mClock.currentTimeMillis()) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, key + " snoozed");
                }
                return true;
            }
            mSnoozedPackages.remove(packageName);
        }
        return false;
    }

    /**
     * Snoozes all current Heads Up Notifications.
     */
    public void snooze() {
        for (String key : mAlertEntries.keySet()) {
            AlertEntry entry = getHeadsUpEntry(key);
            String packageName = entry.mEntry.notification.getPackageName();
            mSnoozedPackages.put(snoozeKey(packageName, mUser),
                    mClock.currentTimeMillis() + mSnoozeLengthMs);
        }
    }

    @NonNull
    private static String snoozeKey(@NonNull String packageName, int user) {
        return user + "," + packageName;
    }

    @Nullable
    protected HeadsUpEntry getHeadsUpEntry(@NonNull String key) {
        return (HeadsUpEntry) mAlertEntries.get(key);
    }

    /**
     * Returns the top Heads Up Notification, which appears to show at first.
     */
    @Nullable
    public NotificationEntry getTopEntry() {
        HeadsUpEntry topEntry = getTopHeadsUpEntry();
        return (topEntry != null) ? topEntry.mEntry : null;
    }

    @Nullable
    protected HeadsUpEntry getTopHeadsUpEntry() {
        if (mAlertEntries.isEmpty()) {
            return null;
        }
        HeadsUpEntry topEntry = null;
        for (AlertEntry entry: mAlertEntries.values()) {
            if (topEntry == null || entry.compareTo(topEntry) < 0) {
                topEntry = (HeadsUpEntry) entry;
            }
        }
        return topEntry;
    }

    /**
     * Sets the current user.
     */
    public void setUser(int user) {
        mUser = user;
    }

    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("HeadsUpManager state:");
        dumpInternal(fd, pw, args);
    }

    protected void dumpInternal(
            @NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("  mTouchAcceptanceDelay="); pw.println(mTouchAcceptanceDelay);
        pw.print("  mSnoozeLengthMs="); pw.println(mSnoozeLengthMs);
        pw.print("  now="); pw.println(mClock.currentTimeMillis());
        pw.print("  mUser="); pw.println(mUser);
        for (AlertEntry entry: mAlertEntries.values()) {
            pw.print("  HeadsUpEntry="); pw.println(entry.mEntry);
        }
        int N = mSnoozedPackages.size();
        pw.println("  snoozed packages: " + N);
        for (int i = 0; i < N; i++) {
            pw.print("    "); pw.print(mSnoozedPackages.valueAt(i));
            pw.print(", "); pw.println(mSnoozedPackages.keyAt(i));
        }
    }

    /**
     * Returns if there are any pinned Heads Up Notifications or not.
     */
    public boolean hasPinnedHeadsUp() {
        return mHasPinnedNotification;
    }

    private boolean hasPinnedNotificationInternal() {
        for (String key : mAlertEntries.keySet()) {
            AlertEntry entry = getHeadsUpEntry(key);
            if (entry.mEntry.isRowPinned()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpins all pinned Heads Up Notifications.
     * @param userUnPinned The unpinned action is trigger by user real operation.
     */
    public void unpinAll(boolean userUnPinned) {
        for (String key : mAlertEntries.keySet()) {
            HeadsUpEntry entry = getHeadsUpEntry(key);
            setEntryPinned(entry, false /* isPinned */);
            // maybe it got un sticky
            entry.updateEntry(false /* updatePostTime */);

            // when the user unpinned all of HUNs by moving one HUN, all of HUNs should not stay
            // on the screen.
            if (userUnPinned && entry.mEntry != null) {
                if (entry.mEntry.mustStayOnScreen()) {
                    entry.mEntry.setHeadsUpIsVisible();
                }
            }
        }
    }

    /**
     * Returns the value of the tracking-heads-up flag. See the doc of {@code setTrackingHeadsUp} as
     * well.
     */
    public boolean isTrackingHeadsUp() {
        // Might be implemented in subclass.
        return false;
    }

    /**
     * Compare two entries and decide how they should be ranked.
     *
     * @return -1 if the first argument should be ranked higher than the second, 1 if the second
     * one should be ranked higher and 0 if they are equal.
     */
    public int compare(@NonNull NotificationEntry a, @NonNull NotificationEntry b) {
        AlertEntry aEntry = getHeadsUpEntry(a.key);
        AlertEntry bEntry = getHeadsUpEntry(b.key);
        if (aEntry == null || bEntry == null) {
            return aEntry == null ? 1 : -1;
        }
        return aEntry.compareTo(bEntry);
    }

    /**
     * Set an entry to be expanded and therefore stick in the heads up area if it's pinned
     * until it's collapsed again.
     */
    public void setExpanded(@NonNull NotificationEntry entry, boolean expanded) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.key);
        if (headsUpEntry != null && entry.isRowPinned()) {
            headsUpEntry.setExpanded(expanded);
        }
    }

    @NonNull
    @Override
    protected HeadsUpEntry createAlertEntry() {
        return new HeadsUpEntry();
    }

    public void onDensityOrFontScaleChanged() {
    }

    /**
     * This represents a notification and how long it is in a heads up mode. It also manages its
     * lifecycle automatically when created.
     */
    protected class HeadsUpEntry extends AlertEntry {
        public boolean remoteInputActive;
        protected boolean expanded;

        @Override
        protected boolean isSticky() {
            return (mEntry.isRowPinned() && expanded)
                    || remoteInputActive || hasFullScreenIntent(mEntry);
        }

        @Override
        public int compareTo(@NonNull AlertEntry alertEntry) {
            HeadsUpEntry headsUpEntry = (HeadsUpEntry) alertEntry;
            boolean isPinned = mEntry.isRowPinned();
            boolean otherPinned = headsUpEntry.mEntry.isRowPinned();
            if (isPinned && !otherPinned) {
                return -1;
            } else if (!isPinned && otherPinned) {
                return 1;
            }
            boolean selfFullscreen = hasFullScreenIntent(mEntry);
            boolean otherFullscreen = hasFullScreenIntent(headsUpEntry.mEntry);
            if (selfFullscreen && !otherFullscreen) {
                return -1;
            } else if (!selfFullscreen && otherFullscreen) {
                return 1;
            }

            if (remoteInputActive && !headsUpEntry.remoteInputActive) {
                return -1;
            } else if (!remoteInputActive && headsUpEntry.remoteInputActive) {
                return 1;
            }

            return super.compareTo(headsUpEntry);
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }

        @Override
        public void reset() {
            super.reset();
            expanded = false;
            remoteInputActive = false;
        }

        @Override
        protected long calculatePostTime() {
            // The actual post time will be just after the heads-up really slided in
            return super.calculatePostTime() + mTouchAcceptanceDelay;
        }

        @Override
        protected long calculateFinishTime() {
            return mPostTime + getRecommendedTimeoutMillis();
        }

        /**
         * Get user-preferred or default timeout duration. The larger one will be returned.
         * @return milliseconds before auto-dismiss
         */
        private int getRecommendedTimeoutMillis() {
            return mAccessibilityMgr.getRecommendedTimeoutMillis(
                    mAutoDismissNotificationDecay,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS
                            | AccessibilityManager.FLAG_CONTENT_ICONS
                            | AccessibilityManager.FLAG_CONTENT_TEXT);
        }
    }
}
