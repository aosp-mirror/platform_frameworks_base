/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.legacy;

import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

import androidx.collection.ArraySet;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A manager that ensures that notifications are visually stable. It will suppress reorderings
 * and reorder at the right time when they are out of view.
 */
public class VisualStabilityManager implements OnHeadsUpChangedListener, Dumpable {

    private static final long TEMPORARY_REORDERING_ALLOWED_DURATION = 1000;

    private final ArrayList<Callback> mReorderingAllowedCallbacks = new ArrayList<>();
    private final ArraySet<Callback> mPersistentReorderingCallbacks = new ArraySet<>();
    private final ArrayList<Callback> mGroupChangesAllowedCallbacks = new ArrayList<>();
    private final ArraySet<Callback> mPersistentGroupCallbacks = new ArraySet<>();
    private final Handler mHandler;
    private final VisualStabilityProvider mVisualStabilityProvider;

    private boolean mPanelExpanded;
    private boolean mScreenOn;
    private boolean mReorderingAllowed;
    private boolean mGroupChangedAllowed;
    private boolean mIsTemporaryReorderingAllowed;
    private long mTemporaryReorderingStart;
    private VisibilityLocationProvider mVisibilityLocationProvider;
    private ArraySet<View> mAllowedReorderViews = new ArraySet<>();
    private ArraySet<NotificationEntry> mLowPriorityReorderingViews = new ArraySet<>();
    private ArraySet<View> mAddedChildren = new ArraySet<>();
    private boolean mPulsing;

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public VisualStabilityManager(
            NotificationEntryManager notificationEntryManager,
            VisualStabilityProvider visualStabilityProvider,
            @Main Handler handler,
            StatusBarStateController statusBarStateController,
            WakefulnessLifecycle wakefulnessLifecycle,
            DumpManager dumpManager) {

        mVisualStabilityProvider = visualStabilityProvider;
        mHandler = handler;
        dumpManager.registerDumpable(this);

        if (notificationEntryManager != null) {
            notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
                @Override
                public void onPreEntryUpdated(NotificationEntry entry) {
                    final boolean ambientStateHasChanged =
                            entry.isAmbient() != entry.getRow().isLowPriority();
                    if (ambientStateHasChanged) {
                        // note: entries are removed in onReorderingFinished
                        mLowPriorityReorderingViews.add(entry);
                    }
                }
            });
        }

        if (statusBarStateController != null) {
            setPulsing(statusBarStateController.isPulsing());
            statusBarStateController.addCallback(new StatusBarStateController.StateListener() {
                @Override
                public void onPulsingChanged(boolean pulsing) {
                    setPulsing(pulsing);
                }

                @Override
                public void onExpandedChanged(boolean expanded) {
                    setPanelExpanded(expanded);
                }
            });
        }

        if (wakefulnessLifecycle != null) {
            wakefulnessLifecycle.addObserver(mWakefulnessObserver);
        }
    }

    /**
     * Add a callback to invoke when reordering is allowed again.
     *
     * @param callback the callback to add
     * @param persistent {@code true} if this callback should this callback be persisted, otherwise
     *                               it will be removed after a single invocation
     */
    public void addReorderingAllowedCallback(Callback callback, boolean persistent) {
        if (persistent) {
            mPersistentReorderingCallbacks.add(callback);
        }
        if (mReorderingAllowedCallbacks.contains(callback)) {
            return;
        }
        mReorderingAllowedCallbacks.add(callback);
    }

    /**
     * Add a callback to invoke when group changes are allowed again.
     *
     * @param callback the callback to add
     * @param persistent {@code true} if this callback should this callback be persisted, otherwise
     *                               it will be removed after a single invocation
     */
    public void addGroupChangesAllowedCallback(Callback callback, boolean persistent) {
        if (persistent) {
            mPersistentGroupCallbacks.add(callback);
        }
        if (mGroupChangesAllowedCallbacks.contains(callback)) {
            return;
        }
        mGroupChangesAllowedCallbacks.add(callback);
    }

    /**
     * @param screenOn whether the screen is on
     */
    private void setScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        updateAllowedStates();
    }

    /**
     * Set the panel to be expanded.
     */
    private void setPanelExpanded(boolean expanded) {
        mPanelExpanded = expanded;
        updateAllowedStates();
    }

    /**
     * @param pulsing whether we are currently pulsing for ambient display.
     */
    private void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
        updateAllowedStates();
    }

    private void updateAllowedStates() {
        boolean reorderingAllowed =
                (!mScreenOn || !mPanelExpanded || mIsTemporaryReorderingAllowed) && !mPulsing;
        boolean changedToTrue = reorderingAllowed && !mReorderingAllowed;
        mReorderingAllowed = reorderingAllowed;
        if (changedToTrue) {
            notifyChangeAllowed(mReorderingAllowedCallbacks, mPersistentReorderingCallbacks);
        }
        mVisualStabilityProvider.setReorderingAllowed(reorderingAllowed);
        boolean groupChangesAllowed = (!mScreenOn || !mPanelExpanded) && !mPulsing;
        changedToTrue = groupChangesAllowed && !mGroupChangedAllowed;
        mGroupChangedAllowed = groupChangesAllowed;
        if (changedToTrue) {
            notifyChangeAllowed(mGroupChangesAllowedCallbacks, mPersistentGroupCallbacks);
        }
    }

    private void notifyChangeAllowed(ArrayList<Callback> callbacks,
            ArraySet<Callback> persistentCallbacks) {
        for (int i = 0; i < callbacks.size(); i++) {
            Callback callback = callbacks.get(i);
            callback.onChangeAllowed();
            if (!persistentCallbacks.contains(callback)) {
                callbacks.remove(callback);
                i--;
            }
        }
    }

    /**
     * @return whether reordering is currently allowed in general.
     */
    public boolean isReorderingAllowed() {
        return mReorderingAllowed;
    }

    /**
     * @return whether changes in the grouping should be allowed right now.
     */
    public boolean areGroupChangesAllowed() {
        return mGroupChangedAllowed;
    }

    /**
     * @return whether a specific notification is allowed to reorder. Certain notifications are
     * allowed to reorder even if {@link #isReorderingAllowed()} returns false, like newly added
     * notifications or heads-up notifications that are out of view.
     */
    public boolean canReorderNotification(ExpandableNotificationRow row) {
        if (mReorderingAllowed) {
            return true;
        }
        if (mAddedChildren.contains(row)) {
            return true;
        }
        if (mLowPriorityReorderingViews.contains(row.getEntry())) {
            return true;
        }
        if (mAllowedReorderViews.contains(row)
                && !mVisibilityLocationProvider.isInVisibleLocation(row.getEntry())) {
            return true;
        }
        return false;
    }

    public void setVisibilityLocationProvider(
            VisibilityLocationProvider visibilityLocationProvider) {
        mVisibilityLocationProvider = visibilityLocationProvider;
    }

    /**
     * Notifications have been reordered, so reset all the allowed list of views that are allowed
     * to reorder.
     */
    public void onReorderingFinished() {
        mAllowedReorderViews.clear();
        mAddedChildren.clear();
        mLowPriorityReorderingViews.clear();
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        if (isHeadsUp) {
            // Heads up notifications should in general be allowed to reorder if they are out of
            // view and stay at the current location if they aren't.
            mAllowedReorderViews.add(entry.getRow());
        }
    }

    /**
     * Temporarily allows reordering of the entire shade for a period of 1000ms. Subsequent calls
     * to this method will extend the timer.
     */
    public void temporarilyAllowReordering() {
        mHandler.removeCallbacks(mOnTemporaryReorderingExpired);
        mHandler.postDelayed(mOnTemporaryReorderingExpired, TEMPORARY_REORDERING_ALLOWED_DURATION);
        if (!mIsTemporaryReorderingAllowed) {
            mTemporaryReorderingStart = SystemClock.elapsedRealtime();
        }
        mIsTemporaryReorderingAllowed = true;
        updateAllowedStates();
    }

    private final Runnable mOnTemporaryReorderingExpired = () -> {
        mIsTemporaryReorderingAllowed = false;
        updateAllowedStates();
    };

    /**
     * Notify the visual stability manager that a new view was added and should be allowed to
     * reorder next time.
     */
    public void notifyViewAddition(View view) {
        mAddedChildren.add(view);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("VisualStabilityManager state:");
        pw.print("  mIsTemporaryReorderingAllowed="); pw.println(mIsTemporaryReorderingAllowed);
        pw.print("  mTemporaryReorderingStart="); pw.println(mTemporaryReorderingStart);

        long now = SystemClock.elapsedRealtime();
        pw.print("    Temporary reordering window has been open for ");
        pw.print(now - (mIsTemporaryReorderingAllowed ? mTemporaryReorderingStart : now));
        pw.println("ms");

        pw.println();
    }

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            setScreenOn(false);
        }

        @Override
        public void onStartedWakingUp() {
            setScreenOn(true);
        }
    };


    /**
     * See {@link Callback#onChangeAllowed()}
     */
    public interface Callback {

        /**
         * Called when changing is allowed again.
         */
        void onChangeAllowed();
    }
}
