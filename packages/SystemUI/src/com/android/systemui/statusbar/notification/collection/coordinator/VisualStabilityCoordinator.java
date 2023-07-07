/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_ASLEEP;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeStateEvents;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.Compile;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * Ensures that notifications are visually stable if the user is looking at the notifications.
 * Group and section changes are re-allowed when the notification entries are no longer being
 * viewed.
 */
// TODO(b/204468557): Move to @CoordinatorScope
@SysUISingleton
public class VisualStabilityCoordinator implements Coordinator, Dumpable,
        ShadeStateEvents.ShadeStateEventsListener {
    public static final String TAG = "VisualStability";
    public static final boolean DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    private final DelayableExecutor mDelayableExecutor;
    private final HeadsUpManager mHeadsUpManager;
    private final ShadeStateEvents mShadeStateEvents;
    private final StatusBarStateController mStatusBarStateController;
    private final VisibilityLocationProvider mVisibilityLocationProvider;
    private final VisualStabilityProvider mVisualStabilityProvider;
    private final WakefulnessLifecycle mWakefulnessLifecycle;

    private boolean mSleepy = true;
    private boolean mFullyDozed;
    private boolean mPanelExpanded;
    private boolean mPulsing;
    private boolean mNotifPanelCollapsing;
    private boolean mNotifPanelLaunchingActivity;

    private boolean mPipelineRunAllowed;
    private boolean mReorderingAllowed;
    private boolean mIsSuppressingPipelineRun = false;
    private boolean mIsSuppressingGroupChange = false;
    private final Set<String> mEntriesWithSuppressedSectionChange = new HashSet<>();
    private boolean mIsSuppressingEntryReorder = false;

    // key: notification key that can temporarily change its section
    // value: runnable that when run removes its associated RemoveOverrideSuppressionRunnable
    // from the DelayableExecutor's queue
    private Map<String, Runnable> mEntriesThatCanChangeSection = new HashMap<>();

    @VisibleForTesting
    protected static final long ALLOW_SECTION_CHANGE_TIMEOUT = 500;

    @Inject
    public VisualStabilityCoordinator(
            DelayableExecutor delayableExecutor,
            DumpManager dumpManager,
            HeadsUpManager headsUpManager,
            ShadeStateEvents shadeStateEvents,
            StatusBarStateController statusBarStateController,
            VisibilityLocationProvider visibilityLocationProvider,
            VisualStabilityProvider visualStabilityProvider,
            WakefulnessLifecycle wakefulnessLifecycle) {
        mHeadsUpManager = headsUpManager;
        mVisibilityLocationProvider = visibilityLocationProvider;
        mVisualStabilityProvider = visualStabilityProvider;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mDelayableExecutor = delayableExecutor;
        mShadeStateEvents = shadeStateEvents;

        dumpManager.registerDumpable(this);
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mSleepy = mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP;
        mFullyDozed = mStatusBarStateController.getDozeAmount() == 1f;

        mStatusBarStateController.addCallback(mStatusBarStateControllerListener);
        mPulsing = mStatusBarStateController.isPulsing();
        mShadeStateEvents.addShadeStateEventsListener(this);

        pipeline.setVisualStabilityManager(mNotifStabilityManager);
    }

    // TODO(b/203826051): Ensure stability manager can allow reordering off-screen
    //  HUNs to the top of the shade
    private final NotifStabilityManager mNotifStabilityManager =
            new NotifStabilityManager("VisualStabilityCoordinator") {
                private boolean canMoveForHeadsUp(NotificationEntry entry) {
                    return entry != null && mHeadsUpManager.isAlerting(entry.getKey())
                            && !mVisibilityLocationProvider.isInVisibleLocation(entry);
                }

                @Override
                public void onBeginRun() {
                    mIsSuppressingPipelineRun = false;
                    mIsSuppressingGroupChange = false;
                    mEntriesWithSuppressedSectionChange.clear();
                    mIsSuppressingEntryReorder = false;
                }

                @Override
                public boolean isPipelineRunAllowed() {
                    mIsSuppressingPipelineRun |= !mPipelineRunAllowed;
                    return mPipelineRunAllowed;
                }

                @Override
                public boolean isGroupChangeAllowed(@NonNull NotificationEntry entry) {
                    final boolean isGroupChangeAllowedForEntry =
                            mReorderingAllowed || canMoveForHeadsUp(entry);
                    mIsSuppressingGroupChange |= !isGroupChangeAllowedForEntry;
                    return isGroupChangeAllowedForEntry;
                }

                @Override
                public boolean isGroupPruneAllowed(@NonNull GroupEntry entry) {
                    final boolean isGroupPruneAllowedForEntry = mReorderingAllowed;
                    mIsSuppressingGroupChange |= !isGroupPruneAllowedForEntry;
                    return isGroupPruneAllowedForEntry;
                }

                @Override
                public boolean isSectionChangeAllowed(@NonNull NotificationEntry entry) {
                    final boolean isSectionChangeAllowedForEntry =
                            mReorderingAllowed
                                    || canMoveForHeadsUp(entry)
                                    || mEntriesThatCanChangeSection.containsKey(entry.getKey());
                    if (!isSectionChangeAllowedForEntry) {
                        mEntriesWithSuppressedSectionChange.add(entry.getKey());
                    }
                    return isSectionChangeAllowedForEntry;
                }

                @Override
                public boolean isEntryReorderingAllowed(@NonNull ListEntry entry) {
                    return mReorderingAllowed || canMoveForHeadsUp(entry.getRepresentativeEntry());
                }

                @Override
                public boolean isEveryChangeAllowed() {
                    return mReorderingAllowed;
                }

                @Override
                public void onEntryReorderSuppressed() {
                    mIsSuppressingEntryReorder = true;
                }
            };

    private void updateAllowedStates(String field, boolean value) {
        boolean wasPipelineRunAllowed = mPipelineRunAllowed;
        boolean wasReorderingAllowed = mReorderingAllowed;
        mPipelineRunAllowed = !isPanelCollapsingOrLaunchingActivity();
        mReorderingAllowed = isReorderingAllowed();
        if (DEBUG && (wasPipelineRunAllowed != mPipelineRunAllowed
                || wasReorderingAllowed != mReorderingAllowed)) {
            Log.d(TAG, "Stability allowances changed:"
                    + "  pipelineRunAllowed " + wasPipelineRunAllowed + "->" + mPipelineRunAllowed
                    + "  reorderingAllowed " + wasReorderingAllowed + "->" + mReorderingAllowed
                    + "  when setting " + field + "=" + value);
        }
        if (mPipelineRunAllowed && mIsSuppressingPipelineRun) {
            mNotifStabilityManager.invalidateList("pipeline run suppression ended");
        } else if (mReorderingAllowed && (mIsSuppressingGroupChange
                || isSuppressingSectionChange()
                || mIsSuppressingEntryReorder)) {
            String reason = "reorder suppression ended for"
                    + " group=" + mIsSuppressingGroupChange
                    + " section=" + isSuppressingSectionChange()
                    + " sort=" + mIsSuppressingEntryReorder;
            mNotifStabilityManager.invalidateList(reason);
        }
        mVisualStabilityProvider.setReorderingAllowed(mReorderingAllowed);
    }

    private boolean isSuppressingSectionChange() {
        return !mEntriesWithSuppressedSectionChange.isEmpty();
    }

    private boolean isPanelCollapsingOrLaunchingActivity() {
        return mNotifPanelCollapsing || mNotifPanelLaunchingActivity;
    }

    private boolean isReorderingAllowed() {
        return ((mFullyDozed && mSleepy) || !mPanelExpanded) && !mPulsing;
    }

    /**
     * Allows this notification entry to be re-ordered in the notification list temporarily until
     * the timeout has passed.
     *
     * Typically this is allowed because the user has directly changed something about the
     * notification and we are reordering based on the user's change.
     *
     * @param entry notification entry that can change sections even if isReorderingAllowed is false
     * @param now current time SystemClock.uptimeMillis
     */
    public void temporarilyAllowSectionChanges(@NonNull NotificationEntry entry, long now) {
        final String entryKey = entry.getKey();
        final boolean wasSectionChangeAllowed =
                mNotifStabilityManager.isSectionChangeAllowed(entry);

        // If it exists, cancel previous timeout
        if (mEntriesThatCanChangeSection.containsKey(entryKey)) {
            mEntriesThatCanChangeSection.get(entryKey).run();
        }

        // Schedule & store new timeout cancellable
        mEntriesThatCanChangeSection.put(
                entryKey,
                mDelayableExecutor.executeAtTime(
                        () -> mEntriesThatCanChangeSection.remove(entryKey),
                        now + ALLOW_SECTION_CHANGE_TIMEOUT));

        if (!wasSectionChangeAllowed) {
            mNotifStabilityManager.invalidateList("temporarilyAllowSectionChanges");
        }
    }

    final StatusBarStateController.StateListener mStatusBarStateControllerListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onPulsingChanged(boolean pulsing) {
                    mPulsing = pulsing;
                    updateAllowedStates("pulsing", pulsing);
                }

                @Override
                public void onExpandedChanged(boolean expanded) {
                    mPanelExpanded = expanded;
                    updateAllowedStates("panelExpanded", expanded);
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    final boolean fullyDozed = linear == 1f;
                    mFullyDozed = fullyDozed;
                    updateAllowedStates("fullyDozed", fullyDozed);
                }
            };

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            // NOTE: this method is called much earlier than what we consider "finished" going to
            // sleep (the animation isn't done), so we also need to check the doze amount is not 1
            // and use the combo to determine that the locked shade is not visible.
            mSleepy = true;
            updateAllowedStates("sleepy", true);
        }

        @Override
        public void onStartedWakingUp() {
            mSleepy = false;
            updateAllowedStates("sleepy", false);
        }
    };

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("pipelineRunAllowed: " + mPipelineRunAllowed);
        pw.println("  notifPanelCollapsing: " + mNotifPanelCollapsing);
        pw.println("  launchingNotifActivity: " + mNotifPanelLaunchingActivity);
        pw.println("reorderingAllowed: " + mReorderingAllowed);
        pw.println("  sleepy: " + mSleepy);
        pw.println("  fullyDozed: " + mFullyDozed);
        pw.println("  panelExpanded: " + mPanelExpanded);
        pw.println("  pulsing: " + mPulsing);
        pw.println("isSuppressingPipelineRun: " + mIsSuppressingPipelineRun);
        pw.println("isSuppressingGroupChange: " + mIsSuppressingGroupChange);
        pw.println("isSuppressingEntryReorder: " + mIsSuppressingEntryReorder);
        pw.println("entriesWithSuppressedSectionChange: "
                + mEntriesWithSuppressedSectionChange.size());
        for (String key : mEntriesWithSuppressedSectionChange) {
            pw.println("  " + key);
        }
        pw.println("entriesThatCanChangeSection: " + mEntriesThatCanChangeSection.size());
        for (String key : mEntriesThatCanChangeSection.keySet()) {
            pw.println("  " + key);
        }
    }

    @Override
    public void onPanelCollapsingChanged(boolean isCollapsing) {
        mNotifPanelCollapsing = isCollapsing;
        updateAllowedStates("notifPanelCollapsing", isCollapsing);
    }

    @Override
    public void onLaunchingActivityChanged(boolean isLaunchingActivity) {
        mNotifPanelLaunchingActivity = isLaunchingActivity;
        updateAllowedStates("notifPanelLaunchingActivity", isLaunchingActivity);
    }
}
