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
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Stack;
import java.util.TreeMap;

public class HeadsUpManager {
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

    private TreeMap<String ,HeadsUpEntry> mHeadsUpEntries = new TreeMap<>();
    private HashSet<String> mSwipedOutKeys = new HashSet<>();
    private int mUser;
    private Clock mClock;
    private boolean mReleaseOnExpandFinish;
    private boolean mTrackingHeadsUp;
    private HashSet<NotificationData.Entry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private boolean mIsExpanded;
    private boolean mHasPinnedHeadsUp;

    public HeadsUpManager(final Context context) {
        Resources resources = context.getResources();
        mTouchSensitivityDelay = resources.getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
        mSnoozedPackages = new ArrayMap<>();
        mDefaultSnoozeLengthMs = resources.getInteger(R.integer.heads_up_default_snooze_length_ms);
        mSnoozeLengthMs = mDefaultSnoozeLengthMs;
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mHeadsUpNotificationDecay = 2000000;
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

        // TODO: investigate whether this is still needed
//        if (!mHeadsUpEntries.isEmpty()) {
//             whoops, we're on already!
//             showNotification(mHeadsUpEntries);
//        }
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
        updatePinnedHeadsUpState(false);
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
        boolean wasEmpty = mHeadsUpEntries.isEmpty();
        HeadsUpEntry headsUpEntry = mEntryPool.acquire();
        headsUpEntry.setEntry(entry);
        mHeadsUpEntries.put(entry.key, headsUpEntry);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.OnHeadsUpStateChanged(entry, true);
        }
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.row.setHeadsUp(true);
    }

    private void removeHeadsUpEntry(NotificationData.Entry entry) {
        HeadsUpEntry remove = mHeadsUpEntries.remove(entry.key);
        mEntryPool.release(remove);
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.row.setHeadsUp(false);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.OnHeadsUpStateChanged(entry, false);
        }
        updatePinnedHeadsUpState(false);
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
        for (String key: mHeadsUpEntries.keySet()) {
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

    public TreeMap<String, HeadsUpEntry> getEntries() {
        return mHeadsUpEntries;
    }

    public HeadsUpEntry getTopEntry() {
        return mHeadsUpEntries.isEmpty() ? null : mHeadsUpEntries.lastEntry().getValue();
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
        // TODO: Look into touchable region
//        mContentHolder.getLocationOnScreen(mTmpTwoArray);
//
//        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
//        info.touchableRegion.set(mTmpTwoArray[0], mTmpTwoArray[1],
//                mTmpTwoArray[0] + mContentHolder.getWidth(),
//                mTmpTwoArray[1] + mContentHolder.getHeight());
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
        for (String key: mHeadsUpEntries.keySet()) {
            pw.print("  HeadsUpEntry="); pw.println(mHeadsUpEntries.get(key));
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
        for (String key: mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
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
        }

        @Override
        public int compareTo(HeadsUpEntry o) {
            return postTime < o.postTime ? -1
                    : postTime == o.postTime ? 0
                            : 1;
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
