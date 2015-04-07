/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pools;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;
import java.util.TreeSet;

public class HeadsUpManager implements ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "HeadsUpManager";
    private static final boolean DEBUG = false;
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";

    private final int mHeadsUpNotificationDecay;
    private final int mMinimumDisplayTime;

    private final int mTouchSensitivityDelay;
    private final ArrayMap<String, Long> mSnoozedPackages;
    private final HashSet<OnHeadsUpChangedListener> mListeners = new HashSet<>();
    private final int mDefaultSnoozeLengthMs;
    private final Handler mHandler = new Handler();
    private final Pools.Pool<HeadsUpEntry> mEntryPool = new Pools.Pool<HeadsUpEntry>() {

        private Stack<HeadsUpEntry> mPoolObjects = new Stack<>();

        @Override
        public HeadsUpEntry acquire() {
            if (!mPoolObjects.isEmpty()) {
                return mPoolObjects.pop();
            }
            return new HeadsUpEntry();
        }

        @Override
        public boolean release(HeadsUpEntry instance) {
            instance.removeAutoCancelCallbacks();
            mPoolObjects.push(instance);
            return true;
        }
    };


    private PhoneStatusBar mBar;
    private int mSnoozeLengthMs;
    private ContentObserver mSettingsObserver;
    private HashMap<String, HeadsUpEntry> mHeadsUpEntries = new HashMap<>();
    private TreeSet<HeadsUpEntry> mSortedEntries = new TreeSet<>();
    private HashSet<String> mSwipedOutKeys = new HashSet<>();
    private int mUser;
    private Clock mClock;
    private boolean mReleaseOnExpandFinish;
    private boolean mTrackingHeadsUp;
    private HashSet<NotificationData.Entry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private boolean mIsExpanded;
    private boolean mHasPinnedHeadsUp;
    private int[] mTmpTwoArray = new int[2];

    public HeadsUpManager(final Context context, ViewTreeObserver observer) {
        Resources resources = context.getResources();
        mTouchSensitivityDelay = resources.getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
        mSnoozedPackages = new ArrayMap<>();
        mDefaultSnoozeLengthMs = resources.getInteger(R.integer.heads_up_default_snooze_length_ms);
        mSnoozeLengthMs = mDefaultSnoozeLengthMs;
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mHeadsUpNotificationDecay = 200000000/*resources.getInteger(R.integer.heads_up_notification_decay)*/;;
        mClock = new Clock();
        // TODO: shadow mSwipeHelper.setMaxSwipeProgress(mMaxAlpha);

        mSnoozeLengthMs = Settings.Global.getInt(context.getContentResolver(),
                SETTING_HEADS_UP_SNOOZE_LENGTH_MS, mDefaultSnoozeLengthMs);
        mSettingsObserver = new ContentObserver(mHandler) {
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
                mSettingsObserver);
        if (DEBUG) Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);
        observer.addOnComputeInternalInsetsListener(this);
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public void addListener(OnHeadsUpChangedListener listener) {
        mListeners.add(listener);
    }

    public PhoneStatusBar getBar() {
        return mBar;
    }

    /**
     * Called when posting a new notification to the heads up.
     */
    public void showNotification(NotificationData.Entry headsUp) {
        if (DEBUG) Log.v(TAG, "showNotification");
        addHeadsUpEntry(headsUp);
        updateNotification(headsUp, true);
        headsUp.setInterruption();
    }

    /**
     * Called when updating or posting a notification to the heads up.
     */
    public void updateNotification(NotificationData.Entry headsUp, boolean alert) {
        if (DEBUG) Log.v(TAG, "updateNotification");

        headsUp.row.setChildrenExpanded(false /* expanded */, false /* animated */);
        headsUp.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

        if (alert) {
            HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(headsUp.key);
            headsUpEntry.updateEntry();
            headsUpEntry.entry.row.setInShade(mIsExpanded);
        }
    }

    private void addHeadsUpEntry(NotificationData.Entry entry) {
        HeadsUpEntry headsUpEntry = mEntryPool.acquire();

        // This will also add the entry to the sortedList
        headsUpEntry.setEntry(entry);
        mHeadsUpEntries.put(entry.key, headsUpEntry);
        entry.row.setHeadsUp(true);
        if (!entry.row.isInShade() && mIsExpanded) {
            headsUpEntry.entry.row.setInShade(true);
        }
        updatePinnedHeadsUpState(false);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.OnHeadsUpStateChanged(entry, true);
        }
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private void removeHeadsUpEntry(NotificationData.Entry entry) {
        HeadsUpEntry remove = mHeadsUpEntries.remove(entry.key);
        mSortedEntries.remove(remove);
        mEntryPool.release(remove);
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.row.setHeadsUp(false);
        updatePinnedHeadsUpState(false);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.OnHeadsUpStateChanged(entry, false);
        }
    }

    private void updatePinnedHeadsUpState(boolean forceImmediate) {
        boolean hasPinnedHeadsUp = hasPinnedHeadsUpInternal();
        if (hasPinnedHeadsUp == mHasPinnedHeadsUp) {
            return;
        }
        mHasPinnedHeadsUp = hasPinnedHeadsUp;
        for (OnHeadsUpChangedListener listener :mListeners) {
            listener.OnPinnedHeadsUpExistChanged(hasPinnedHeadsUp, forceImmediate);
        }
    }

    /**
     * React to the removal of the notification in the heads up.
     *
     * @return true if the notification was removed and false if it still needs to be kept around
     * for a bit since it wasn't shown long enough
     */
    public boolean removeNotification(String key) {
        if (DEBUG) Log.v(TAG, "remove");
        if (wasShownLongEnough(key)) {
            releaseImmediately(key);
            return true;
        } else {
            getHeadsUpEntry(key).hideAsSoonAsPossible();
            return false;
        }
    }

    private boolean wasShownLongEnough(String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        HeadsUpEntry topEntry = getTopEntry();
        if (mSwipedOutKeys.contains(key)) {
            // We always instantly dismiss views being manually swiped out.
            mSwipedOutKeys.remove(key);
            return true;
        }
        if (headsUpEntry != topEntry) {
            return true;
        }
        return headsUpEntry.wasShownLongEnough();
    }

    public boolean isHeadsUp(String key) {
        return mHeadsUpEntries.containsKey(key);
    }


    /**
     * Push any current Heads Up notification down into the shade.
     */
    public void releaseAllImmediately() {
        if (DEBUG) Log.v(TAG, "releaseAllImmediately");
        HashSet<String> keys = new HashSet<>(mHeadsUpEntries.keySet());
        for (String key: keys) {
            releaseImmediately(key);
        }
    }

    public void releaseImmediately(String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (headsUpEntry == null) {
            return;
        }
        NotificationData.Entry shadeEntry = headsUpEntry.entry;
        removeHeadsUpEntry(shadeEntry);
    }

    public boolean isSnoozed(String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > SystemClock.elapsedRealtime()) {
                if (DEBUG) Log.v(TAG, key + " snoozed");
                return true;
            }
            mSnoozedPackages.remove(packageName);
        }
        return false;
    }

    public void snooze() {
        for (String key: mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            String packageName = entry.entry.notification.getPackageName();
            mSnoozedPackages.put(snoozeKey(packageName, mUser),
                    SystemClock.elapsedRealtime() + mSnoozeLengthMs);
        }
        mReleaseOnExpandFinish = true;
    }

    private static String snoozeKey(String packageName, int user) {
        return user + "," + packageName;
    }

    private HeadsUpEntry getHeadsUpEntry(String key) {
        return mHeadsUpEntries.get(key);
    }

    public NotificationData.Entry getEntry(String key) {
        return mHeadsUpEntries.get(key).entry;
    }

    public TreeSet<HeadsUpEntry> getSortedEntries() {
        return mSortedEntries;
    }

    public HeadsUpEntry getTopEntry() {
        return mSortedEntries.isEmpty() ? null : mSortedEntries.first();
    }

    /**
     * @param key the key of the touched notification
     * @return whether the touch is valid and should not be discarded
     */
    public boolean shouldSwallowClick(String key) {
        if (mClock.currentTimeMillis() < mHeadsUpEntries.get(key).postTime) {
            return true;
        }
        return false;
    }

    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        // TODO: handle the shadow
        //getBackground().setAlpha((int) (255 * swipeProgress));
        return false;
    }

    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        if (!mIsExpanded && mHasPinnedHeadsUp) {
            int minX = Integer.MAX_VALUE;
            int maxX = 0;
            int minY = Integer.MAX_VALUE;
            int maxY = 0;
            for (HeadsUpEntry entry: mSortedEntries) {
                ExpandableNotificationRow row = entry.entry.row;
                if (!row.isInShade()) {
                    row.getLocationOnScreen(mTmpTwoArray);
                    minX = Math.min(minX, mTmpTwoArray[0]);
                    minY = Math.min(minY, 0);
                    maxX = Math.max(maxX, mTmpTwoArray[0] + row.getWidth());
                    maxY = Math.max(maxY, row.getHeadsUpHeight());
                }
            }

            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(minX, minY, maxX, maxY);
        }
    }

    public void setUser(int user) {
        mUser = user;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HeadsUpManager state:");
        pw.print("  mTouchSensitivityDelay="); pw.println(mTouchSensitivityDelay);
        pw.print("  mSnoozeLengthMs="); pw.println(mSnoozeLengthMs);
        pw.print("  now="); pw.println(SystemClock.elapsedRealtime());
        pw.print("  mUser="); pw.println(mUser);
        for (HeadsUpEntry entry: mSortedEntries) {
            pw.print("  HeadsUpEntry="); pw.println(entry.entry);
        }
        int N = mSnoozedPackages.size();
        pw.println("  snoozed packages: " + N);
        for (int i = 0; i < N; i++) {
            pw.print("    "); pw.print(mSnoozedPackages.valueAt(i));
            pw.print(", "); pw.println(mSnoozedPackages.keyAt(i));
        }
    }

    public boolean hasPinnedHeadsUp() {
        return mHasPinnedHeadsUp;
    }

    private boolean hasPinnedHeadsUpInternal() {
        for (String key: mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            if (!entry.entry.row.isInShade()) {
                return true;
            }
        }
        return false;
    }

    public void addSwipedOutKey(String key) {
        mSwipedOutKeys.add(key);
    }

    public float getHighestPinnedHeadsUp() {
        float max = 0;
        for (HeadsUpEntry entry: mSortedEntries) {
            if (!entry.entry.row.isInShade()) {
                max = Math.max(max, entry.entry.row.getActualHeight());
            }
        }
        return max;
    }

    public void releaseAllToShade() {
        for (String key: mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            entry.entry.row.setInShade(true);
        }
        updatePinnedHeadsUpState(true);
    }

    public void onExpandingFinished() {
        if (mReleaseOnExpandFinish) {
            releaseAllImmediately();
            mReleaseOnExpandFinish = false;
        } else {
            for (NotificationData.Entry entry : mEntriesToRemoveAfterExpand) {
                removeHeadsUpEntry(entry);
            }
            mEntriesToRemoveAfterExpand.clear();
        }
    }

    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    public void setIsExpanded(boolean isExpanded) {
        mIsExpanded = isExpanded;
    }

    public int getTopHeadsUpHeight() {
        HeadsUpEntry topEntry = getTopEntry();
        return topEntry != null ? topEntry.entry.row.getHeadsUpHeight() : 0;
    }

    public class HeadsUpEntry implements Comparable<HeadsUpEntry> {
        public NotificationData.Entry entry;
        public long postTime;
        public long earliestRemovaltime;
        private Runnable mRemoveHeadsUpRunnable;

        public void setEntry(final NotificationData.Entry entry) {
            this.entry = entry;

            // The actual post time will be just after the heads-up really slided in
            postTime = mClock.currentTimeMillis() + mTouchSensitivityDelay;
            mRemoveHeadsUpRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!mTrackingHeadsUp) {
                        removeHeadsUpEntry(entry);
                    } else {
                        mEntriesToRemoveAfterExpand.add(entry);
                    }
                }
            };
            updateEntry();
        }

        public void updateEntry() {
            long currentTime = mClock.currentTimeMillis();
            postTime = Math.max(postTime, currentTime);
            long finishTime = postTime + mHeadsUpNotificationDecay;
            long removeDelay = Math.max(finishTime - currentTime, mMinimumDisplayTime);
            earliestRemovaltime = currentTime + mMinimumDisplayTime;
            removeAutoCancelCallbacks();
            mHandler.postDelayed(mRemoveHeadsUpRunnable, removeDelay);
            updateSortOrder(HeadsUpEntry.this);
        }

        @Override
        public int compareTo(HeadsUpEntry o) {
            return postTime < o.postTime ? 1
                    : postTime == o.postTime ? 0
                            : -1;
        }

        public void removeAutoCancelCallbacks() {
            mHandler.removeCallbacks(mRemoveHeadsUpRunnable);
        }

        public boolean wasShownLongEnough() {
            return earliestRemovaltime < mClock.currentTimeMillis();
        }

        public void hideAsSoonAsPossible() {
            removeAutoCancelCallbacks();
            mHandler.postDelayed(mRemoveHeadsUpRunnable,
                    earliestRemovaltime - mClock.currentTimeMillis());
        }
    }

    /**
     * Update the sorted heads up order.
     *
     * @param headsUpEntry the headsUp that changed
     */
    private void updateSortOrder(HeadsUpEntry headsUpEntry) {
        mSortedEntries.remove(headsUpEntry);
        mSortedEntries.add(headsUpEntry);
    }

    public static class Clock {
        public long currentTimeMillis() {
            return SystemClock.elapsedRealtime();
        }
    }

    public interface OnHeadsUpChangedListener {
        void OnPinnedHeadsUpExistChanged(boolean exist, boolean changeImmediatly);
        void OnHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp);
    }
}
