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

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_WAKING;

import android.annotation.NonNull;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * Ensures that notifications are visually stable if the user is looking at the notifications.
 * Group and section changes are re-allowed when the notification entries are no longer being
 * viewed.
 *
 * Previously this was implemented in the view-layer {@link NotificationViewHierarchyManager} by
 * {@link com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager}.
 * This is now integrated in the data-layer via
 * {@link com.android.systemui.statusbar.notification.collection.ShadeListBuilder}.
 */
@SysUISingleton
public class VisualStabilityCoordinator implements Coordinator {
    private final DelayableExecutor mDelayableExecutor;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final StatusBarStateController mStatusBarStateController;
    private final HeadsUpManager mHeadsUpManager;

    private boolean mScreenOn;
    private boolean mPanelExpanded;
    private boolean mPulsing;

    private boolean mReorderingAllowed;
    private boolean mIsSuppressingGroupChange = false;
    private final Set<String> mEntriesWithSuppressedSectionChange = new HashSet<>();

    // key: notification key that can temporarily change its section
    // value: runnable that when run removes its associated RemoveOverrideSuppressionRunnable
    // from the DelayableExecutor's queue
    private Map<String, Runnable> mEntriesThatCanChangeSection = new HashMap<>();

    @VisibleForTesting
    protected static final long ALLOW_SECTION_CHANGE_TIMEOUT = 500;

    @Inject
    public VisualStabilityCoordinator(
            HeadsUpManager headsUpManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            StatusBarStateController statusBarStateController,
            DelayableExecutor delayableExecutor
    ) {
        mHeadsUpManager = headsUpManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mDelayableExecutor = delayableExecutor;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mScreenOn = mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_AWAKE
                || mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_WAKING;

        mStatusBarStateController.addCallback(mStatusBarStateControllerListener);
        mPulsing = mStatusBarStateController.isPulsing();

        pipeline.setVisualStabilityManager(mNotifStabilityManager);
    }

    private final NotifStabilityManager mNotifStabilityManager =
            new NotifStabilityManager("VisualStabilityCoordinator") {
                @Override
                public void onBeginRun() {
                    mIsSuppressingGroupChange = false;
                    mEntriesWithSuppressedSectionChange.clear();
                }

                @Override
                public boolean isGroupChangeAllowed(NotificationEntry entry) {
                    final boolean isGroupChangeAllowedForEntry =
                            mReorderingAllowed || mHeadsUpManager.isAlerting(entry.getKey());
                    mIsSuppressingGroupChange |= !isGroupChangeAllowedForEntry;
                    return isGroupChangeAllowedForEntry;
                }

                @Override
                public boolean isSectionChangeAllowed(NotificationEntry entry) {
                    final boolean isSectionChangeAllowedForEntry =
                            mReorderingAllowed
                                    || mHeadsUpManager.isAlerting(entry.getKey())
                                    || mEntriesThatCanChangeSection.containsKey(entry.getKey());
                    if (isSectionChangeAllowedForEntry) {
                        mEntriesWithSuppressedSectionChange.add(entry.getKey());
                    }
                    return isSectionChangeAllowedForEntry;
                }
            };

    private void updateAllowedStates() {
        mReorderingAllowed = isReorderingAllowed();
        if (mReorderingAllowed && (mIsSuppressingGroupChange || isSuppressingSectionChange())) {
            mNotifStabilityManager.invalidateList();
        }
    }

    private boolean isSuppressingSectionChange() {
        return !mEntriesWithSuppressedSectionChange.isEmpty();
    }

    private boolean isReorderingAllowed() {
        return (!mScreenOn || !mPanelExpanded) && !mPulsing;
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
            mNotifStabilityManager.invalidateList();
        }
    }

    final StatusBarStateController.StateListener mStatusBarStateControllerListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onPulsingChanged(boolean pulsing) {
                    mPulsing = pulsing;
                    updateAllowedStates();
                }

                @Override
                public void onExpandedChanged(boolean expanded) {
                    mPanelExpanded = expanded;
                    updateAllowedStates();
                }
            };

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mScreenOn = false;
            updateAllowedStates();
        }

        @Override
        public void onStartedWakingUp() {
            mScreenOn = true;
            updateAllowedStates();
        }
    };
}
