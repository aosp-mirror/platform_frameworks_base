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
import android.graphics.Region.Op;
import android.util.Log;
import android.util.Pools;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.collection.ArraySet;

import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarStateController.StateListener;
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

/**
 * A implementation of HeadsUpManager for phone and car.
 */
public class HeadsUpManagerPhone extends HeadsUpManager implements Dumpable,
        ViewTreeObserver.OnComputeInternalInsetsListener, VisualStabilityManager.Callback,
        OnHeadsUpChangedListener, ConfigurationController.ConfigurationListener, StateListener {
    private static final String TAG = "HeadsUpManagerPhone";

    private final View mStatusBarWindowView;
    private final NotificationGroupManager mGroupManager;
    private final StatusBar mBar;
    private final VisualStabilityManager mVisualStabilityManager;
    private boolean mReleaseOnExpandFinish;

    private int mStatusBarHeight;
    private int mHeadsUpInset;
    private int mDisplayCutoutTouchableRegionSize;
    private boolean mTrackingHeadsUp;
    private HashSet<String> mSwipedOutKeys = new HashSet<>();
    private HashSet<NotificationEntry> mEntriesToRemoveAfterExpand = new HashSet<>();
    private ArraySet<NotificationEntry> mEntriesToRemoveWhenReorderingAllowed
            = new ArraySet<>();
    private boolean mIsExpanded;
    private int[] mTmpTwoArray = new int[2];
    private boolean mHeadsUpGoingAway;
    private boolean mWaitingOnCollapseWhenGoingAway;
    private boolean mBubbleGoingAway;
    private boolean mIsObserving;
    private int mStatusBarState;

    private AnimationStateHandler mAnimationStateHandler;
    private BubbleController mBubbleController = Dependency.get(BubbleController.class);

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

    public HeadsUpManagerPhone(@NonNull final Context context, @NonNull View statusBarWindowView,
            @NonNull NotificationGroupManager groupManager, @NonNull StatusBar bar,
            @NonNull VisualStabilityManager visualStabilityManager) {
        super(context);

        mStatusBarWindowView = statusBarWindowView;
        mGroupManager = groupManager;
        mBar = bar;
        mVisualStabilityManager = visualStabilityManager;

        initResources();

        addListener(new OnHeadsUpChangedListener() {
            @Override
            public void onHeadsUpPinnedModeChanged(boolean hasPinnedNotification) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "onHeadsUpPinnedModeChanged");
                }
                updateTouchableRegionListener();
            }
        });
        Dependency.get(StatusBarStateController.class).addCallback(this);
        mBubbleController.setBubbleStateChangeListener((hasBubbles) -> {
            if (!hasBubbles) {
                mBubbleGoingAway = true;
            }
            updateTouchableRegionListener();
        });
    }

    public void setAnimationStateHandler(AnimationStateHandler handler) {
        mAnimationStateHandler = handler;
    }

    public void destroy() {
        Dependency.get(StatusBarStateController.class).removeCallback(this);
    }

    private void initResources() {
        Resources resources = mContext.getResources();
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mHeadsUpInset = mStatusBarHeight + resources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mDisplayCutoutTouchableRegionSize = resources.getDimensionPixelSize(
                R.dimen.display_cutout_touchable_region_size);
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
                // make sure our state is sane
                mWaitingOnCollapseWhenGoingAway = false;
                mHeadsUpGoingAway = false;
                updateTouchableRegionListener();
            }
            if (mBubbleController.hasBubbles() || !mIsExpanded) {
                updateTouchableRegionListener();
            }
        }
    }

    @Override
    public void onStateChanged(int newState) {
        mStatusBarState = newState;
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
     * Notifies that a remote input textbox in notification gets active or inactive.
     * @param entry The entry of the target notification.
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

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  ViewTreeObserver.OnComputeInternalInsetsListener overrides:

    /**
     * Overridden from TreeObserver.
     */
    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        if (mIsExpanded || mBar.isBouncerShowing()) {
            // The touchable region is always the full area when expanded
            return;
        }
        if (hasPinnedHeadsUp()) {
            NotificationEntry topEntry = getTopEntry();
            if (topEntry.isChildInGroup()) {
                final NotificationEntry groupSummary
                        = mGroupManager.getGroupSummary(topEntry.notification);
                if (groupSummary != null) {
                    topEntry = groupSummary;
                }
            }
            ExpandableNotificationRow topRow = topEntry.getRow();
            topRow.getLocationOnScreen(mTmpTwoArray);
            int minX = mTmpTwoArray[0];
            int maxX = mTmpTwoArray[0] + topRow.getWidth();
            int height = topRow.getIntrinsicHeight();

            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(minX, 0, maxX, mHeadsUpInset + height);
        } else {
            setCollapsedTouchableInsets(info);
        }
        Rect r = mBubbleController.getTouchableRegion();
        if (r != null) {
            info.touchableRegion.union(r);
        }
        mBubbleGoingAway = false;
    }

    private void setCollapsedTouchableInsets(ViewTreeObserver.InternalInsetsInfo info) {
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(0, 0, mStatusBarWindowView.getWidth(), mStatusBarHeight);
        updateRegionForNotch(info.touchableRegion);
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
        region.op(bounds, Op.UNION);
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
        Resources resources = mContext.getResources();
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
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
        super.onAlertEntryRemoved(alertEntry);
        mEntryPool.release((HeadsUpEntryPhone) alertEntry);
    }

    @Override
    protected boolean shouldHeadsUpBecomePinned(NotificationEntry entry) {
          return mStatusBarState != StatusBarState.KEYGUARD && !mIsExpanded
                  || super.shouldHeadsUpBecomePinned(entry);
    }

    @Override
    protected void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpInternal(fd, pw, args);
        pw.print("  mBarState="); pw.println(mStatusBarState);
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

    // TODO: some kind of TouchableRegionManager to deal with this, HeadsUpManager is not really
    // the right place
    private void updateTouchableRegionListener() {
        boolean shouldObserve = hasPinnedHeadsUp() || mHeadsUpGoingAway
                || mBubbleController.hasBubbles() || mBubbleGoingAway
                || mWaitingOnCollapseWhenGoingAway
                || mStatusBarWindowView.getRootWindowInsets().getDisplayCutout() != null;
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

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpEntryPhone:

    protected class HeadsUpEntryPhone extends HeadsUpManager.HeadsUpEntry {

        private boolean mMenuShownPinned;

        @Override
        protected boolean isSticky() {
            return super.isSticky() || mMenuShownPinned;
        }

        public void setEntry(@NonNull final NotificationEntry entry) {
           Runnable removeHeadsUpRunnable = () -> {
                if (!mVisualStabilityManager.isReorderingAllowed()) {
                    mEntriesToRemoveWhenReorderingAllowed.add(entry);
                    mVisualStabilityManager.addReorderingAllowedCallback(
                            HeadsUpManagerPhone.this);
                } else if (!mTrackingHeadsUp) {
                    removeAlertEntry(entry.key);
                } else {
                    mEntriesToRemoveAfterExpand.add(entry);
                }
            };

            setEntry(entry, removeHeadsUpRunnable);
        }

        @Override
        public void updateEntry(boolean updatePostTime) {
            super.updateEntry(updatePostTime);

            if (mEntriesToRemoveAfterExpand.contains(mEntry)) {
                mEntriesToRemoveAfterExpand.remove(mEntry);
            }
            if (mEntriesToRemoveWhenReorderingAllowed.contains(mEntry)) {
                mEntriesToRemoveWhenReorderingAllowed.remove(mEntry);
            }
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
        }
    }

    public interface AnimationStateHandler {
        void setHeadsUpGoingAwayAnimationsAllowed(boolean allowed);
    }
}
