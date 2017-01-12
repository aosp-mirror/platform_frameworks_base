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

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pools;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;

/**
 * A manager which handles heads up notifications which is a special mode where
 * they simply peek from the top of the screen.
 */
public class HeadsUpManager implements ViewTreeObserver.OnComputeInternalInsetsListener,
        VisualStabilityManager.Callback {
    private static final String TAG = "HeadsUpManager";
    private static final boolean DEBUG = false;
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";
    private static final int TAG_CLICKED_NOTIFICATION = R.id.is_clicked_heads_up_tag;

    private final int mHeadsUpNotificationDecay;
    private final int mMinimumDisplayTime;

    private final int mTouchAcceptanceDelay;
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
            instance.reset();
            mPoolObjects.push(instance);
            return true;
        }
    };

    private final View mStatusBarWindowView;
    private final int mStatusBarHeight;
    private final Context mContext;
    private final NotificationGroupManager mGroupManager;
    private PhoneStatusBar mBar;
    private int mSnoozeLengthMs;
    private ContentObserver mSettingsObserver;
    private HashMap<String, HeadsUpEntry> mHeadsUpEntries = new HashMap<>();
    private HashSet<String> mSwipedOutKeys = new HashSet<>();
    private int mUser;
    private Clock mClock;
    private boolean mReleaseOnExpandFinish;
    private boolean mTrackingHeadsUp;
    private HashSet<NotificationData.Entry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private ArraySet<NotificationData.Entry> mEntriesToRemoveWhenReorderingAllowed
            = new ArraySet<>();
    private boolean mIsExpanded;
    private boolean mHasPinnedNotification;
    private int[] mTmpTwoArray = new int[2];
    private boolean mHeadsUpGoingAway;
    private boolean mWaitingOnCollapseWhenGoingAway;
    private boolean mIsObserving;
    private boolean mRemoteInputActive;
    private VisualStabilityManager mVisualStabilityManager;
    private int mStatusBarState;

    public HeadsUpManager(final Context context, View statusBarWindowView,
                          NotificationGroupManager groupManager) {
        mContext = context;
        Resources resources = mContext.getResources();
        mTouchAcceptanceDelay = resources.getInteger(R.integer.touch_acceptance_delay);
        mSnoozedPackages = new ArrayMap<>();
        mDefaultSnoozeLengthMs = resources.getInteger(R.integer.heads_up_default_snooze_length_ms);
        mSnoozeLengthMs = mDefaultSnoozeLengthMs;
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mHeadsUpNotificationDecay = resources.getInteger(R.integer.heads_up_notification_decay);
        mClock = new Clock();

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
        mStatusBarWindowView = statusBarWindowView;
        mGroupManager = groupManager;
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
    }

    private void updateTouchableRegionListener() {
        boolean shouldObserve = mHasPinnedNotification || mHeadsUpGoingAway
                || mWaitingOnCollapseWhenGoingAway;
        if (shouldObserve == mIsObserving) {
            return;
        }
        if (shouldObserve) {
            mStatusBarWindowView.getViewTreeObserver().addOnComputeInternalInsetsListener(this);
            mStatusBarWindowView.requestLayout();
        } else {
            mStatusBarWindowView.getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        }
        mIsObserving = shouldObserve;
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

        headsUp.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

        if (alert) {
            HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(headsUp.key);
            if (headsUpEntry == null) {
                // the entry was released before this update (i.e by a listener) This can happen
                // with the groupmanager
                return;
            }
            headsUpEntry.updateEntry();
            setEntryPinned(headsUpEntry, shouldHeadsUpBecomePinned(headsUp));
        }
    }

    private void addHeadsUpEntry(NotificationData.Entry entry) {
        HeadsUpEntry headsUpEntry = mEntryPool.acquire();

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

    private boolean shouldHeadsUpBecomePinned(NotificationData.Entry entry) {
        return mStatusBarState != StatusBarState.KEYGUARD
                && !mIsExpanded || hasFullScreenIntent(entry);
    }

    private boolean hasFullScreenIntent(NotificationData.Entry entry) {
        return entry.notification.getNotification().fullScreenIntent != null;
    }

    private void setEntryPinned(HeadsUpEntry headsUpEntry, boolean isPinned) {
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

    private void removeHeadsUpEntry(NotificationData.Entry entry) {
        HeadsUpEntry remove = mHeadsUpEntries.remove(entry.key);
        entry.row.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        entry.row.setHeadsUp(false);
        setEntryPinned(remove, false /* isPinned */);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, false);
        }
        mEntryPool.release(remove);
    }

    private void updatePinnedMode() {
        boolean hasPinnedNotification = hasPinnedNotificationInternal();
        if (hasPinnedNotification == mHasPinnedNotification) {
            return;
        }
        mHasPinnedNotification = hasPinnedNotification;
        if (mHasPinnedNotification) {
            MetricsLogger.count(mContext, "note_peek", 1);
        }
        updateTouchableRegionListener();
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
    public boolean removeNotification(String key, boolean ignoreEarliestRemovalTime) {
        if (DEBUG) Log.v(TAG, "remove");
        if (wasShownLongEnough(key) || ignoreEarliestRemovalTime) {
            releaseImmediately(key);
            return true;
        } else {
            getHeadsUpEntry(key).removeAsSoonAsPossible();
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
        ArrayList<String> keys = new ArrayList<>(mHeadsUpEntries.keySet());
        for (String key : keys) {
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
        for (String key : mHeadsUpEntries.keySet()) {
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

    public Collection<HeadsUpEntry> getAllEntries() {
        return mHeadsUpEntries.values();
    }

    public HeadsUpEntry getTopEntry() {
        if (mHeadsUpEntries.isEmpty()) {
            return null;
        }
        HeadsUpEntry topEntry = null;
        for (HeadsUpEntry entry: mHeadsUpEntries.values()) {
            if (topEntry == null || entry.compareTo(topEntry) == -1) {
                topEntry = entry;
            }
        }
        return topEntry;
    }

    /**
     * Decides whether a click is invalid for a notification, i.e it has not been shown long enough
     * that a user might have consciously clicked on it.
     *
     * @param key the key of the touched notification
     * @return whether the touch is invalid and should be discarded
     */
    public boolean shouldSwallowClick(String key) {
        HeadsUpEntry entry = mHeadsUpEntries.get(key);
        if (entry != null && mClock.currentTimeMillis() < entry.postTime) {
            return true;
        }
        return false;
    }

    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        if (mIsExpanded || mBar.isBouncerShowing()) {
            // The touchable region is always the full area when expanded
            return;
        }
        if (mHasPinnedNotification) {
            ExpandableNotificationRow topEntry = getTopEntry().entry.row;
            if (topEntry.isChildInGroup()) {
                final ExpandableNotificationRow groupSummary
                        = mGroupManager.getGroupSummary(topEntry.getStatusBarNotification());
                if (groupSummary != null) {
                    topEntry = groupSummary;
                }
            }
            topEntry.getLocationOnScreen(mTmpTwoArray);
            int minX = mTmpTwoArray[0];
            int maxX = mTmpTwoArray[0] + topEntry.getWidth();
            int maxY = topEntry.getIntrinsicHeight();

            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(minX, 0, maxX, maxY);
        } else if (mHeadsUpGoingAway || mWaitingOnCollapseWhenGoingAway) {
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(0, 0, mStatusBarWindowView.getWidth(), mStatusBarHeight);
        }
    }

    public void setUser(int user) {
        mUser = user;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HeadsUpManager state:");
        pw.print("  mTouchAcceptanceDelay="); pw.println(mTouchAcceptanceDelay);
        pw.print("  mSnoozeLengthMs="); pw.println(mSnoozeLengthMs);
        pw.print("  now="); pw.println(SystemClock.elapsedRealtime());
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
     * Notifies that a notification was swiped out and will be removed.
     *
     * @param key the notification key
     */
    public void addSwipedOutNotification(String key) {
        mSwipedOutKeys.add(key);
    }

    public void unpinAll() {
        for (String key : mHeadsUpEntries.keySet()) {
            HeadsUpEntry entry = mHeadsUpEntries.get(key);
            setEntryPinned(entry, false /* isPinned */);
            // maybe it got un sticky
            entry.updateEntry(false /* updatePostTime */);
        }
    }

    public void onExpandingFinished() {
        if (mReleaseOnExpandFinish) {
            releaseAllImmediately();
            mReleaseOnExpandFinish = false;
        } else {
            for (NotificationData.Entry entry : mEntriesToRemoveAfterExpand) {
                if (isHeadsUp(entry.key)) {
                    // Maybe the heads-up was removed already
                    removeHeadsUpEntry(entry);
                }
            }
        }
        mEntriesToRemoveAfterExpand.clear();
    }

    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    public boolean isTrackingHeadsUp() {
        return mTrackingHeadsUp;
    }

    public void setIsExpanded(boolean isExpanded) {
        if (isExpanded != mIsExpanded) {
            mIsExpanded = isExpanded;
            if (isExpanded) {
                // make sure our state is sane
                mWaitingOnCollapseWhenGoingAway = false;
                mHeadsUpGoingAway = false;
                updateTouchableRegionListener();
            }
        }
    }

    /**
     * @return the height of the top heads up notification when pinned. This is different from the
     *         intrinsic height, which also includes whether the notification is system expanded and
     *         is mainly used when dragging down from a heads up notification.
     */
    public int getTopHeadsUpPinnedHeight() {
        HeadsUpEntry topEntry = getTopEntry();
        if (topEntry == null || topEntry.entry == null) {
            return 0;
        }
        ExpandableNotificationRow row = topEntry.entry.row;
        if (row.isChildInGroup()) {
            final ExpandableNotificationRow groupSummary
                    = mGroupManager.getGroupSummary(row.getStatusBarNotification());
            if (groupSummary != null) {
                row = groupSummary;
            }
        }
        return row.getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
    }

    /**
     * Compare two entries and decide how they should be ranked.
     *
     * @return -1 if the first argument should be ranked higher than the second, 1 if the second
     * one should be ranked higher and 0 if they are equal.
     */
    public int compare(NotificationData.Entry a, NotificationData.Entry b) {
        HeadsUpEntry aEntry = getHeadsUpEntry(a.key);
        HeadsUpEntry bEntry = getHeadsUpEntry(b.key);
        if (aEntry == null || bEntry == null) {
            return aEntry == null ? 1 : -1;
        }
        return aEntry.compareTo(bEntry);
    }

    /**
     * Set that we are exiting the headsUp pinned mode, but some notifications might still be
     * animating out. This is used to keep the touchable regions in a sane state.
     */
    public void setHeadsUpGoingAway(boolean headsUpGoingAway) {
        if (headsUpGoingAway != mHeadsUpGoingAway) {
            mHeadsUpGoingAway = headsUpGoingAway;
            if (!headsUpGoingAway) {
                waitForStatusBarLayout();
            }
            updateTouchableRegionListener();
        }
    }

    /**
     * We need to wait on the whole panel to collapse, before we can remove the touchable region
     * listener.
     */
    private void waitForStatusBarLayout() {
        mWaitingOnCollapseWhenGoingAway = true;
        mStatusBarWindowView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft,
                    int oldTop, int oldRight, int oldBottom) {
                if (mStatusBarWindowView.getHeight() <= mStatusBarHeight) {
                    mStatusBarWindowView.removeOnLayoutChangeListener(this);
                    mWaitingOnCollapseWhenGoingAway = false;
                    updateTouchableRegionListener();
                }
            }
        });
    }

    public static void setIsClickedNotification(View child, boolean clicked) {
        child.setTag(TAG_CLICKED_NOTIFICATION, clicked ? true : null);
    }

    public static boolean isClickedHeadsUpNotification(View child) {
        Boolean clicked = (Boolean) child.getTag(TAG_CLICKED_NOTIFICATION);
        return clicked != null && clicked;
    }

    public void setRemoteInputActive(NotificationData.Entry entry, boolean remoteInputActive) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(entry.key);
        if (headsUpEntry != null && headsUpEntry.remoteInputActive != remoteInputActive) {
            headsUpEntry.remoteInputActive = remoteInputActive;
            if (remoteInputActive) {
                headsUpEntry.removeAutoRemovalCallbacks();
            } else {
                headsUpEntry.updateEntry(false /* updatePostTime */);
            }
        }
    }

    /**
     * Set an entry to be expanded and therefore stick in the heads up area if it's pinned
     * until it's collapsed again.
     */
    public void setExpanded(NotificationData.Entry entry, boolean expanded) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntries.get(entry.key);
        if (headsUpEntry != null && headsUpEntry.expanded != expanded) {
            headsUpEntry.expanded = expanded;
            if (expanded) {
                headsUpEntry.removeAutoRemovalCallbacks();
            } else {
                headsUpEntry.updateEntry(false /* updatePostTime */);
            }
        }
    }

    @Override
    public void onReorderingAllowed() {
        for (NotificationData.Entry entry : mEntriesToRemoveWhenReorderingAllowed) {
            if (isHeadsUp(entry.key)) {
                // Maybe the heads-up was removed already
                removeHeadsUpEntry(entry);
            }
        }
        mEntriesToRemoveWhenReorderingAllowed.clear();
    }

    public void setVisualStabilityManager(VisualStabilityManager visualStabilityManager) {
        mVisualStabilityManager = visualStabilityManager;
    }

    public void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
    }

    /**
     * This represents a notification and how long it is in a heads up mode. It also manages its
     * lifecycle automatically when created.
     */
    public class HeadsUpEntry implements Comparable<HeadsUpEntry> {
        public NotificationData.Entry entry;
        public long postTime;
        public long earliestRemovaltime;
        private Runnable mRemoveHeadsUpRunnable;
        public boolean remoteInputActive;
        public boolean expanded;

        public void setEntry(final NotificationData.Entry entry) {
            this.entry = entry;

            // The actual post time will be just after the heads-up really slided in
            postTime = mClock.currentTimeMillis() + mTouchAcceptanceDelay;
            mRemoveHeadsUpRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!mVisualStabilityManager.isReorderingAllowed()) {
                        mEntriesToRemoveWhenReorderingAllowed.add(entry);
                        mVisualStabilityManager.addReorderingAllowedCallback(HeadsUpManager.this);
                    } else if (!mTrackingHeadsUp) {
                        removeHeadsUpEntry(entry);
                    } else {
                        mEntriesToRemoveAfterExpand.add(entry);
                    }
                }
            };
            updateEntry();
        }

        public void updateEntry() {
            updateEntry(true);
        }

        public void updateEntry(boolean updatePostTime) {
            long currentTime = mClock.currentTimeMillis();
            earliestRemovaltime = currentTime + mMinimumDisplayTime;
            if (updatePostTime) {
                postTime = Math.max(postTime, currentTime);
            }
            removeAutoRemovalCallbacks();
            if (mEntriesToRemoveAfterExpand.contains(entry)) {
                mEntriesToRemoveAfterExpand.remove(entry);
            }
            if (mEntriesToRemoveWhenReorderingAllowed.contains(entry)) {
                mEntriesToRemoveWhenReorderingAllowed.remove(entry);
            }
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
        public int compareTo(HeadsUpEntry o) {
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

        public void removeAutoRemovalCallbacks() {
            mHandler.removeCallbacks(mRemoveHeadsUpRunnable);
        }

        public boolean wasShownLongEnough() {
            return earliestRemovaltime < mClock.currentTimeMillis();
        }

        public void removeAsSoonAsPossible() {
            removeAutoRemovalCallbacks();
            mHandler.postDelayed(mRemoveHeadsUpRunnable,
                    earliestRemovaltime - mClock.currentTimeMillis());
        }

        public void reset() {
            removeAutoRemovalCallbacks();
            entry = null;
            mRemoveHeadsUpRunnable = null;
            expanded = false;
            remoteInputActive = false;
        }
    }

    public static class Clock {
        public long currentTimeMillis() {
            return SystemClock.elapsedRealtime();
        }
    }

}
