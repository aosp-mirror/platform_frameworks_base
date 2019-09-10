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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.util.Pools;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.collection.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Stack;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A implementation of HeadsUpManager for phone and car.
 */
@Singleton
public class HeadsUpManagerPhone extends HeadsUpManager implements Dumpable,
        VisualStabilityManager.Callback, OnHeadsUpChangedListener,
        ConfigurationController.ConfigurationListener, StateListener {
    private static final String TAG = "HeadsUpManagerPhone";

    @VisibleForTesting
    final int mExtensionTime;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mBypassController;
    private final int mAutoHeadsUpNotificationDecay;
    private View mStatusBarWindowView;
    private NotificationGroupManager mGroupManager;
    private VisualStabilityManager mVisualStabilityManager;
    private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private boolean mReleaseOnExpandFinish;

    private int mStatusBarHeight;
    private int mHeadsUpInset;
    private int mDisplayCutoutTouchableRegionSize;
    private boolean mTrackingHeadsUp;
    private HashSet<String> mSwipedOutKeys = new HashSet<>();
    private HashSet<NotificationEntry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private HashSet<String> mKeysToRemoveWhenLeavingKeyguard = new HashSet<>();
    private ArraySet<NotificationEntry> mEntriesToRemoveWhenReorderingAllowed
            = new ArraySet<>();
    private boolean mIsExpanded;
    private int[] mTmpTwoArray = new int[2];
    private boolean mHeadsUpGoingAway;
    private int mStatusBarState;
    private Region mTouchableRegion = new Region();

    private AnimationStateHandler mAnimationStateHandler;

    private final Pools.Pool<HeadsUpEntryPhone> mEntryPool = new Pools.Pool<HeadsUpEntryPhone>() {
        private Stack<HeadsUpEntryPhone> mPoolObjects = new Stack<>();

        @Override
        public HeadsUpEntryPhone acquire() {
            if (!mPoolObjects.isEmpty()) {
                return mPoolObjects.pop();
            }
            return new HeadsUpEntryPhone();
        }

        @Override
        public boolean release(@NonNull HeadsUpEntryPhone instance) {
            mPoolObjects.push(instance);
            return true;
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Constructor:

    @Inject
    public HeadsUpManagerPhone(@NonNull final Context context,
            StatusBarStateController statusBarStateController,
            KeyguardBypassController bypassController) {
        super(context);
        Resources resources = mContext.getResources();
        mExtensionTime = resources.getInteger(R.integer.ambient_notification_extension_time);
        mAutoHeadsUpNotificationDecay = resources.getInteger(
                R.integer.auto_heads_up_notification_decay);
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(this);
        mBypassController = bypassController;

        initResources();
    }


    public void setUp(@NonNull View statusBarWindowView,
            @NonNull NotificationGroupManager groupManager,
            @NonNull StatusBar bar,
            @NonNull VisualStabilityManager visualStabilityManager) {
        mStatusBarWindowView = statusBarWindowView;
        mStatusBarTouchableRegionManager = new StatusBarTouchableRegionManager(mContext, this, bar,
                statusBarWindowView);
        mGroupManager = groupManager;
        mVisualStabilityManager = visualStabilityManager;

        addListener(new OnHeadsUpChangedListener() {
            @Override
            public void onHeadsUpPinnedModeChanged(boolean hasPinnedNotification) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "onHeadsUpPinnedModeChanged");
                }
                mStatusBarTouchableRegionManager.updateTouchableRegion();
            }
        });
    }

    public void setAnimationStateHandler(AnimationStateHandler handler) {
        mAnimationStateHandler = handler;
    }

    private void initResources() {
        Resources resources = mContext.getResources();
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mHeadsUpInset = mStatusBarHeight + resources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mDisplayCutoutTouchableRegionSize = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.display_cutout_touchable_region_size);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        initResources();
    }

    @Override
    public void onOverlayChanged() {
        initResources();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Public methods:

    /**
     * Decides whether a click is invalid for a notification, i.e it has not been shown long enough
     * that a user might have consciously clicked on it.
     *
     * @param key the key of the touched notification
     * @return whether the touch is invalid and should be discarded
     */
    public boolean shouldSwallowClick(@NonNull String key) {
        HeadsUpManager.HeadsUpEntry entry = getHeadsUpEntry(key);
        return entry != null && mClock.currentTimeMillis() < entry.mPostTime;
    }

    public void onExpandingFinished() {
        if (mReleaseOnExpandFinish) {
            releaseAllImmediately();
            mReleaseOnExpandFinish = false;
        } else {
            for (NotificationEntry entry : mEntriesToRemoveAfterExpand) {
                if (isAlerting(entry.key)) {
                    // Maybe the heads-up was removed already
                    removeAlertEntry(entry.key);
                }
            }
        }
        mEntriesToRemoveAfterExpand.clear();
    }

    /**
     * Sets the tracking-heads-up flag. If the flag is true, HeadsUpManager doesn't remove the entry
     * from the list even after a Heads Up Notification is gone.
     */
    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    /**
     * Notify that the status bar panel gets expanded or collapsed.
     *
     * @param isExpanded True to notify expanded, false to notify collapsed.
     */
    public void setIsPanelExpanded(boolean isExpanded) {
        if (isExpanded != mIsExpanded) {
            mIsExpanded = isExpanded;
            if (isExpanded) {
                mHeadsUpGoingAway = false;
            }
            mStatusBarTouchableRegionManager.setIsStatusBarExpanded(isExpanded);
            mStatusBarTouchableRegionManager.updateTouchableRegion();
        }
    }

    @Override
    public void onStateChanged(int newState) {
        boolean wasKeyguard = mStatusBarState == StatusBarState.KEYGUARD;
        boolean isKeyguard = newState == StatusBarState.KEYGUARD;
        mStatusBarState = newState;
        if (wasKeyguard && !isKeyguard && mKeysToRemoveWhenLeavingKeyguard.size() != 0) {
            String[] keys = mKeysToRemoveWhenLeavingKeyguard.toArray(new String[0]);
            for (String key : keys) {
                removeAlertEntry(key);
            }
            mKeysToRemoveWhenLeavingKeyguard.clear();
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (!isDozing) {
            // Let's make sure all huns we got while dozing time out within the normal timeout
            // duration. Otherwise they could get stuck for a very long time
            for (AlertEntry entry : mAlertEntries.values()) {
                entry.updateEntry(true /* updatePostTime */);
            }
        }
    }

    @Override
    public boolean isEntryAutoHeadsUpped(String key) {
        HeadsUpEntryPhone headsUpEntryPhone = getHeadsUpEntryPhone(key);
        if (headsUpEntryPhone == null) {
            return false;
        }
        return headsUpEntryPhone.isAutoHeadsUp();
    }

    /**
     * Set that we are exiting the headsUp pinned mode, but some notifications might still be
     * animating out. This is used to keep the touchable regions in a sane state.
     */
    public void setHeadsUpGoingAway(boolean headsUpGoingAway) {
        if (headsUpGoingAway != mHeadsUpGoingAway) {
            mHeadsUpGoingAway = headsUpGoingAway;
            if (!headsUpGoingAway) {
                mStatusBarTouchableRegionManager.updateTouchableRegionAfterLayout();
            } else {
                mStatusBarTouchableRegionManager.updateTouchableRegion();
            }
        }
    }

    public boolean isHeadsUpGoingAway() {
        return mHeadsUpGoingAway;
    }

    /**
     * Notifies that a remote input textbox in notification gets active or inactive.
     *
     * @param entry             The entry of the target notification.
     * @param remoteInputActive True to notify active, False to notify inactive.
     */
    public void setRemoteInputActive(
            @NonNull NotificationEntry entry, boolean remoteInputActive) {
        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(entry.key);
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
     * Sets whether an entry's menu row is exposed and therefore it should stick in the heads up
     * area if it's pinned until it's hidden again.
     */
    public void setMenuShown(@NonNull NotificationEntry entry, boolean menuShown) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.key);
        if (headsUpEntry instanceof HeadsUpEntryPhone && entry.isRowPinned()) {
            ((HeadsUpEntryPhone) headsUpEntry).setMenuShownPinned(menuShown);
        }
    }

    /**
     * Extends the lifetime of the currently showing pulsing notification so that the pulse lasts
     * longer.
     */
    public void extendHeadsUp() {
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();
        if (topEntry == null) {
            return;
        }
        topEntry.extendPulse();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager public methods overrides:

    @Override
    public boolean isTrackingHeadsUp() {
        return mTrackingHeadsUp;
    }

    @Override
    public void snooze() {
        super.snooze();
        mReleaseOnExpandFinish = true;
    }

    public void addSwipedOutNotification(@NonNull String key) {
        mSwipedOutKeys.add(key);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Dumpable overrides:

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HeadsUpManagerPhone state:");
        dumpInternal(fd, pw, args);
    }

    /**
     * Update touch insets to include any area needed for touching a heads up notification.
     *
     * @param info Insets that will include heads up notification touch area after execution.
     */
    @Nullable
    public void updateTouchableRegion(ViewTreeObserver.InternalInsetsInfo info) {
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(calculateTouchableRegion());
    }

    public Region calculateTouchableRegion() {
        NotificationEntry topEntry = getTopEntry();
        // This call could be made in an inconsistent state while the pinnedMode hasn't been
        // updated yet, but callbacks leading out of the headsUp manager, querying it. Let's
        // therefore also check if the topEntry is null.
        if (!hasPinnedHeadsUp() || topEntry == null) {
            mTouchableRegion.set(0, 0, mStatusBarWindowView.getWidth(), mStatusBarHeight);
            updateRegionForNotch(mTouchableRegion);

        } else {
            if (topEntry.isChildInGroup()) {
                final NotificationEntry groupSummary =
                        mGroupManager.getGroupSummary(topEntry.notification);
                if (groupSummary != null) {
                    topEntry = groupSummary;
                }
            }
            ExpandableNotificationRow topRow = topEntry.getRow();
            topRow.getLocationOnScreen(mTmpTwoArray);
            int minX = mTmpTwoArray[0];
            int maxX = mTmpTwoArray[0] + topRow.getWidth();
            int height = topRow.getIntrinsicHeight();
            mTouchableRegion.set(minX, 0, maxX, mHeadsUpInset + height);
        }
        return mTouchableRegion;
    }

    private void updateRegionForNotch(Region region) {
        DisplayCutout cutout = mStatusBarWindowView.getRootWindowInsets().getDisplayCutout();
        if (cutout == null) {
            return;
        }

        // Expand touchable region such that we also catch touches that just start below the notch
        // area.
        Rect bounds = new Rect();
        ScreenDecorations.DisplayCutoutView.boundsFromDirection(cutout, Gravity.TOP, bounds);
        bounds.offset(0, mDisplayCutoutTouchableRegionSize);
        region.union(bounds);
    }

    @Override
    public boolean shouldExtendLifetime(NotificationEntry entry) {
        // We should not defer the removal if reordering isn't allowed since otherwise
        // these won't disappear until reordering is allowed again, which happens only once
        // the notification panel is collapsed again.
        return mVisualStabilityManager.isReorderingAllowed() && super.shouldExtendLifetime(entry);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        initResources();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  VisualStabilityManager.Callback overrides:

    @Override
    public void onReorderingAllowed() {
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(false);
        for (NotificationEntry entry : mEntriesToRemoveWhenReorderingAllowed) {
            if (isAlerting(entry.key)) {
                // Maybe the heads-up was removed already
                removeAlertEntry(entry.key);
            }
        }
        mEntriesToRemoveWhenReorderingAllowed.clear();
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(true);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager utility (protected) methods overrides:

    @Override
    protected HeadsUpEntry createAlertEntry() {
        return mEntryPool.acquire();
    }

    @Override
    protected void onAlertEntryRemoved(AlertEntry alertEntry) {
        mKeysToRemoveWhenLeavingKeyguard.remove(alertEntry.mEntry.key);
        super.onAlertEntryRemoved(alertEntry);
        mEntryPool.release((HeadsUpEntryPhone) alertEntry);
    }

    @Override
    protected boolean shouldHeadsUpBecomePinned(NotificationEntry entry) {
        boolean pin = mStatusBarState == StatusBarState.SHADE && !mIsExpanded;
        if (mBypassController.getBypassEnabled()) {
            pin |= mStatusBarState == StatusBarState.KEYGUARD;
        }
        return pin || super.shouldHeadsUpBecomePinned(entry);
    }

    @Override
    protected void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpInternal(fd, pw, args);
        pw.print("  mBarState=");
        pw.println(mStatusBarState);
        pw.print("  mTouchableRegion=");
        pw.println(mTouchableRegion);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Private utility methods:

    @Nullable
    private HeadsUpEntryPhone getHeadsUpEntryPhone(@NonNull String key) {
        return (HeadsUpEntryPhone) mAlertEntries.get(key);
    }

    @Nullable
    private HeadsUpEntryPhone getTopHeadsUpEntryPhone() {
        return (HeadsUpEntryPhone) getTopHeadsUpEntry();
    }

    @Override
    protected boolean canRemoveImmediately(@NonNull String key) {
        if (mSwipedOutKeys.contains(key)) {
            // We always instantly dismiss views being manually swiped out.
            mSwipedOutKeys.remove(key);
            return true;
        }

        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(key);
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();

        return headsUpEntry == null || headsUpEntry != topEntry || super.canRemoveImmediately(key);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpEntryPhone:

    protected class HeadsUpEntryPhone extends HeadsUpManager.HeadsUpEntry {

        private boolean mMenuShownPinned;

        /**
         * If the time this entry has been on was extended
         */
        private boolean extended;

        /**
         * Was this entry received while on keyguard
         */
        private boolean mIsAutoHeadsUp;


        @Override
        protected boolean isSticky() {
            return super.isSticky() || mMenuShownPinned;
        }

        public void setEntry(@NonNull final NotificationEntry entry) {
            Runnable removeHeadsUpRunnable = () -> {
                if (!mVisualStabilityManager.isReorderingAllowed()
                        // We don't want to allow reordering while pulsing, but headsup need to
                        // time out anyway
                        && !entry.showingPulsing()) {
                    mEntriesToRemoveWhenReorderingAllowed.add(entry);
                    mVisualStabilityManager.addReorderingAllowedCallback(
                            HeadsUpManagerPhone.this);
                } else if (mTrackingHeadsUp) {
                    mEntriesToRemoveAfterExpand.add(entry);
                } else if (mIsAutoHeadsUp && mStatusBarState == StatusBarState.KEYGUARD) {
                    mKeysToRemoveWhenLeavingKeyguard.add(entry.key);
                } else {
                    removeAlertEntry(entry.key);
                }
            };

            setEntry(entry, removeHeadsUpRunnable);
        }

        @Override
        public void updateEntry(boolean updatePostTime) {
            mIsAutoHeadsUp = mEntry.isAutoHeadsUp();
            super.updateEntry(updatePostTime);

            if (mEntriesToRemoveAfterExpand.contains(mEntry)) {
                mEntriesToRemoveAfterExpand.remove(mEntry);
            }
            if (mEntriesToRemoveWhenReorderingAllowed.contains(mEntry)) {
                mEntriesToRemoveWhenReorderingAllowed.remove(mEntry);
            }
            mKeysToRemoveWhenLeavingKeyguard.remove(mEntry.key);
        }

        @Override
        public void setExpanded(boolean expanded) {
            if (this.expanded == expanded) {
                return;
            }

            this.expanded = expanded;
            if (expanded) {
                removeAutoRemovalCallbacks();
            } else {
                updateEntry(false /* updatePostTime */);
            }
        }

        public void setMenuShownPinned(boolean menuShownPinned) {
            if (mMenuShownPinned == menuShownPinned) {
                return;
            }

            mMenuShownPinned = menuShownPinned;
            if (menuShownPinned) {
                removeAutoRemovalCallbacks();
            } else {
                updateEntry(false /* updatePostTime */);
            }
        }

        @Override
        public void reset() {
            super.reset();
            mMenuShownPinned = false;
            extended = false;
            mIsAutoHeadsUp = false;
        }

        private void extendPulse() {
            if (!extended) {
                extended = true;
                updateEntry(false);
            }
        }

        @Override
        public int compareTo(AlertEntry alertEntry) {
            HeadsUpEntryPhone headsUpEntry = (HeadsUpEntryPhone) alertEntry;
            boolean autoShown = isAutoHeadsUp();
            boolean otherAutoShown = headsUpEntry.isAutoHeadsUp();
            if (autoShown && !otherAutoShown) {
                return 1;
            } else if (!autoShown && otherAutoShown) {
                return -1;
            }
            return super.compareTo(alertEntry);
        }

        @Override
        protected long calculateFinishTime() {
            return mPostTime + getDecayDuration() + (extended ? mExtensionTime : 0);
        }

        private int getDecayDuration() {
            if (isAutoHeadsUp()) {
                return getRecommendedHeadsUpTimeoutMs(mAutoHeadsUpNotificationDecay);
            } else {
                return getRecommendedHeadsUpTimeoutMs(mAutoDismissNotificationDecay);
            }
        }

        private boolean isAutoHeadsUp() {
            return mIsAutoHeadsUp;
        }
    }

    public interface AnimationStateHandler {
        void setHeadsUpGoingAwayAnimationsAllowed(boolean allowed);
    }
}
