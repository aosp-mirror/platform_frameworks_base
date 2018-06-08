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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A manager which handles heads up notifications which is a special mode where
 * they simply peek from the top of the screen.
 */
public class HeadsUpManager {
    private static final String TAG = "HeadsUpManager";
    private static final boolean DEBUG = false;
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";

    protected final Clock mClock = new Clock();
    protected final HashSet<OnHeadsUpChangedListener> mListeners = new HashSet<>();
    protected final Handler mHandler = new Handler(Looper.getMainLooper());

    protected final Context mContext;

    protected int mHeadsUpNotificationDecay;
    protected int mMinimumDisplayTime;
    protected int mTouchAcceptanceDelay;
    protected int mSnoozeLengthMs;
    protected boolean mHasPinnedNotification;
    protected int mUser;

    private final HashMap<String, HeadsUpEntry> mHeadsUpEntries = new HashMap<>();
    private final ArrayMap<String, Long> mSnoozedPackages;

    public HeadsUpManager(@NonNull final Context context) {
        mContext = context;
        Resources resources = context.getResources();
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mHeadsUpNotificationDecay = resources.getInteger(R.integer.heads_up_notification_decay);
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
                    if (DEBUG) Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);
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

    /**
     * Called when posting a new notification to the heads up.
     */
    public void showNotification(@NonNull NotificationData.Entry headsUp) {
        if (DEBUG) Log.v(TAG, "showNotification");
        addHeadsUpEntry(headsUp);
        updateNotification(headsUp, true);
        headsUp.setInterruption();
    }

    /**
     * Called when updating or posting a notification to the heads up.
     */
    public void updateNotification(@NonNull NotificationData.Entry headsUp, boolean alert) {
        if (DEBUG) Log.v(TAG, "updateNotification");

        headsUp.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

        if (alert) {
            HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(headsUp.key);
            if (headsUpEntry == null) {
                // the entry was released before this update (i.e by a listener) This can happen
                // with the groupmanager
                return;
            }
            headsUpEntry.updateEntry(true /* updatePostTime */);
            setEntryPinned(headsUpEntry, shouldHeadsUpBecomePinned(headsUp));
        }
    }

    private void addHeadsUpEntry(@NonNull NotificationData.Entry entry) {
        HeadsUpEntry headsUpEntry = createHeadsUpEntry();
        // This will also add the entry to the sortedList
        headsUpEntry.setEntry(entry);
        mHeadsUpEntries.put(entry.key, headsUpEntry);
        entry.row.setHeadsUp(true);
        setEntryPinned(headsUpEntry, shouldHeadsUpBecomePinned(entry));
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, true);
        }
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    protected boolean shouldHeadsUpBecomePinned(@NonNull NotificationData.Entry entry) {
        return hasFullScreenIntent(entry);
    }

    protected boolean hasFullScreenIntent(@NonNull NotificationData.Entry entry) {
        return entry.notification.getNotification().fullScreenIntent != null;
    }

    protected void setEntryPinned(
            @NonNull HeadsUpManager.HeadsUpEntry headsUpEntry, boolean isPinned) {
        if (DEBUG) Log.v(TAG, "setEntryPinned: " + isPinned);
        ExpandableNotificationRow row = headsUpEntry.entry.row;
        if (row.isPinned() != isPinned) {
            row.setPinned(isPinned);
            updatePinnedMode();
            for (OnHeadsUpChangedListener listener : mListeners) {
                if (isPinned) {
                    listener.onHeadsUpPinned(row);
                } else {
                    listener.onHeadsUpUnPinned(row);
                }
            }
        }
    }

    protected void removeHeadsUpEntry(@NonNull NotificationData.Entry entry) {
        HeadsUpEntry remove = mHeadsUpEntries.remove(entry.key);
        onHeadsUpEntryRemoved(remove);
    }

    protected void onHeadsUpEntryRemoved(@NonNull HeadsUpEntry remove) {
        NotificationData.Entry entry = remove.entry;
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.row.setHeadsUp(false);
        setEntryPinned(remove, false /* isPinned */);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, false);
        }
        releaseHeadsUpEntry(remove);
    }

    protected void updatePinnedMode() {
        boolean hasPinnedNotification = hasPinnedNotificationInternal();
        if (hasPinnedNotification == mHasPinnedNotification) {
            return;
        }
        if (DEBUG) {
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
     * React to the removal of the notification in the heads up.
     *
     * @return true if the notification was removed and false if it still needs to be kept around
     * for a bit since it wasn't shown long enough
     */
    public boolean removeNotification(@NonNull String key, boolean ignoreEarliestRemovalTime) {
        if (DEBUG) Log.v(TAG, "removeNotification");
        releaseImmediately(key);
        return true;
    }

    /**
     * Returns if the given notification is in the Heads Up Notification list or not.
     */
    public boolean isHeadsUp(@NonNull String key) {
        return mHeadsUpEntries.containsKey(key);
    }

    /**
     * Pushes any current Heads Up notification down into the shade.
     */
    public void releaseAllImmediately() {
        if (DEBUG) Log.v(TAG, "releaseAllImmediately");
        Iterator<HeadsUpEntry> iterator = mHeadsUpEntries.values().iterator();
        while (iterator.hasNext()) {
            HeadsUpEntry entry = iterator.next();
            iterator.remove();
            onHeadsUpEntryRemoved(entry);
        }
    }

    /**
     * Pushes the given Heads Up notification down into the shade.
     */
    public void releaseImmediately(@NonNull String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (headsUpEntry == null) {
            return;
        }
        NotificationData.Entry shadeEntry = headsUpEntry.entry;
        removeHeadsUpEntry(shadeEntry);
    }

    /**
     * Returns if the given notification is snoozed or not.
     */
    public boolean isSnoozed(@NonNull String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > mClock.currentTimeMillis()) {
                if (DEBUG) Log.v(TAG, key + " snoozed");
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
        for (String key : mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            String packageName = entry.entry.notification.getPackageName();
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
        return mHeadsUpEntries.get(key);
    }

    /**
     * Returns the entry of given Heads Up Notification.
     *
     * @param key Key of heads up notification
     */
    @Nullable
    public NotificationData.Entry getEntry(@NonNull String key) {
        HeadsUpEntry entry = mHeadsUpEntries.get(key);
        return entry != null ? entry.entry : null;
    }

    /**
     * Returns the stream of all current Heads Up Notifications.
     */
    @NonNull
    public Stream<NotificationData.Entry> getAllEntries() {
        return mHeadsUpEntries.values().stream().map(headsUpEntry -> headsUpEntry.entry);
    }

    /**
     * Returns the top Heads Up Notification, which appeares to show at first.
     */
    @Nullable
    public NotificationData.Entry getTopEntry() {
        HeadsUpEntry topEntry = getTopHeadsUpEntry();
        return (topEntry != null) ? topEntry.entry : null;
    }

    /**
     * Returns if any heads up notification is available or not.
     */
    public boolean hasHeadsUpNotifications() {
        return !mHeadsUpEntries.isEmpty();
    }

    @Nullable
    protected HeadsUpEntry getTopHeadsUpEntry() {
        if (mHeadsUpEntries.isEmpty()) {
            return null;
        }
        HeadsUpEntry topEntry = null;
        for (HeadsUpEntry entry: mHeadsUpEntries.values()) {
            if (topEntry == null || entry.compareTo(topEntry) < 0) {
                topEntry = entry;
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
        for (HeadsUpEntry entry: mHeadsUpEntries.values()) {
            pw.print("  HeadsUpEntry="); pw.println(entry.entry);
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
        for (String key : mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            if (entry.entry.row.isPinned()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpins all pinned Heads Up Notifications.
     */
    public void unpinAll() {
        for (String key : mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            setEntryPinned(entry, false /* isPinned */);
            // maybe it got un sticky
            entry.updateEntry(false /* updatePostTime */);
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
    public int compare(@NonNull NotificationData.Entry a, @NonNull NotificationData.Entry b) {
        HeadsUpEntry aEntry = getHeadsUpEntry(a.key);
        HeadsUpEntry bEntry = getHeadsUpEntry(b.key);
        if (aEntry == null || bEntry == null) {
            return aEntry == null ? 1 : -1;
        }
        return aEntry.compareTo(bEntry);
    }

    /**
     * Set an entry to be expanded and therefore stick in the heads up area if it's pinned
     * until it's collapsed again.
     */
    public void setExpanded(@NonNull NotificationData.Entry entry, boolean expanded) {
        HeadsUpManager.HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(entry.key);
        if (headsUpEntry != null && entry.row.isPinned()) {
            headsUpEntry.expanded(expanded);
        }
    }

    @NonNull
    protected HeadsUpEntry createHeadsUpEntry() {
        return new HeadsUpEntry();
    }

    protected void releaseHeadsUpEntry(@NonNull HeadsUpEntry entry) {
        entry.reset();
    }

    public void onDensityOrFontScaleChanged() {
    }

    /**
     * This represents a notification and how long it is in a heads up mode. It also manages its
     * lifecycle automatically when created.
     */
    protected class HeadsUpEntry implements Comparable<HeadsUpEntry> {
        @Nullable public NotificationData.Entry entry;
        public long postTime;
        public boolean remoteInputActive;
        public long earliestRemovaltime;
        public boolean expanded;

        @Nullable private Runnable mRemoveHeadsUpRunnable;

        public void setEntry(@Nullable final NotificationData.Entry entry) {
            setEntry(entry, null);
        }

        public void setEntry(@Nullable final NotificationData.Entry entry,
                @Nullable Runnable removeHeadsUpRunnable) {
            this.entry = entry;
            this.mRemoveHeadsUpRunnable = removeHeadsUpRunnable;

            // The actual post time will be just after the heads-up really slided in
            postTime = mClock.currentTimeMillis() + mTouchAcceptanceDelay;
            updateEntry(true /* updatePostTime */);
        }

        public void updateEntry(boolean updatePostTime) {
            if (DEBUG) Log.v(TAG, "updateEntry");

            long currentTime = mClock.currentTimeMillis();
            earliestRemovaltime = currentTime + mMinimumDisplayTime;
            if (updatePostTime) {
                postTime = Math.max(postTime, currentTime);
            }
            removeAutoRemovalCallbacks();

            if (!isSticky()) {
                long finishTime = postTime + mHeadsUpNotificationDecay;
                long removeDelay = Math.max(finishTime - currentTime, mMinimumDisplayTime);
                mHandler.postDelayed(mRemoveHeadsUpRunnable, removeDelay);
            }
        }

        private boolean isSticky() {
            return (entry.row.isPinned() && expanded)
                    || remoteInputActive || hasFullScreenIntent(entry);
        }

        @Override
        public int compareTo(@NonNull HeadsUpEntry o) {
            boolean isPinned = entry.row.isPinned();
            boolean otherPinned = o.entry.row.isPinned();
            if (isPinned && !otherPinned) {
                return -1;
            } else if (!isPinned && otherPinned) {
                return 1;
            }
            boolean selfFullscreen = hasFullScreenIntent(entry);
            boolean otherFullscreen = hasFullScreenIntent(o.entry);
            if (selfFullscreen && !otherFullscreen) {
                return -1;
            } else if (!selfFullscreen && otherFullscreen) {
                return 1;
            }

            if (remoteInputActive && !o.remoteInputActive) {
                return -1;
            } else if (!remoteInputActive && o.remoteInputActive) {
                return 1;
            }

            return postTime < o.postTime ? 1
                    : postTime == o.postTime ? entry.key.compareTo(o.entry.key)
                            : -1;
        }

        public void expanded(boolean expanded) {
            this.expanded = expanded;
        }

        public void reset() {
            entry = null;
            expanded = false;
            remoteInputActive = false;
            removeAutoRemovalCallbacks();
            mRemoveHeadsUpRunnable = null;
        }

        public void removeAutoRemovalCallbacks() {
            if (mRemoveHeadsUpRunnable != null)
                mHandler.removeCallbacks(mRemoveHeadsUpRunnable);
        }

        public void removeAsSoonAsPossible() {
            if (mRemoveHeadsUpRunnable != null) {
                removeAutoRemovalCallbacks();
                mHandler.postDelayed(mRemoveHeadsUpRunnable,
                        earliestRemovaltime - mClock.currentTimeMillis());
            }
        }
    }

    public static class Clock {
        public long currentTimeMillis() {
            return SystemClock.elapsedRealtime();
        }
    }
}
