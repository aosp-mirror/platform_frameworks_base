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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.Flags;
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.Edge;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scene.shared.model.Scenes;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor;
import com.android.systemui.statusbar.notification.shared.NotificationMinimalism;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.kotlin.BooleanFlowOperators;
import com.android.systemui.util.kotlin.JavaAdapter;

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
public class VisualStabilityCoordinator implements Coordinator, Dumpable {
    private final DelayableExecutor mDelayableExecutor;
    private final HeadsUpManager mHeadsUpManager;
    private final SeenNotificationsInteractor mSeenNotificationsInteractor;
    private final ShadeAnimationInteractor mShadeAnimationInteractor;
    private final StatusBarStateController mStatusBarStateController;
    private final JavaAdapter mJavaAdapter;
    private final VisibilityLocationProvider mVisibilityLocationProvider;
    private final VisualStabilityProvider mVisualStabilityProvider;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final CommunalSceneInteractor mCommunalSceneInteractor;
    private final ShadeInteractor mShadeInteractor;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final VisualStabilityCoordinatorLogger mLogger;

    private boolean mSleepy = true;
    private boolean mFullyDozed;
    private boolean mPanelExpanded;
    private boolean mPulsing;
    private boolean mNotifPanelCollapsing;
    private boolean mNotifPanelLaunchingActivity;
    private boolean mCommunalShowing = false;
    private boolean mLockscreenShowing = false;
    private boolean mLockscreenInGoneTransition = false;

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
            @Background DelayableExecutor delayableExecutor,
            DumpManager dumpManager,
            HeadsUpManager headsUpManager,
            ShadeAnimationInteractor shadeAnimationInteractor,
            JavaAdapter javaAdapter,
            SeenNotificationsInteractor seenNotificationsInteractor,
            StatusBarStateController statusBarStateController,
            VisibilityLocationProvider visibilityLocationProvider,
            VisualStabilityProvider visualStabilityProvider,
            WakefulnessLifecycle wakefulnessLifecycle,
            CommunalSceneInteractor communalSceneInteractor,
            ShadeInteractor shadeInteractor,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            VisualStabilityCoordinatorLogger logger) {
        mHeadsUpManager = headsUpManager;
        mShadeAnimationInteractor = shadeAnimationInteractor;
        mJavaAdapter = javaAdapter;
        mSeenNotificationsInteractor = seenNotificationsInteractor;
        mVisibilityLocationProvider = visibilityLocationProvider;
        mVisualStabilityProvider = visualStabilityProvider;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mStatusBarStateController = statusBarStateController;
        mDelayableExecutor = delayableExecutor;
        mCommunalSceneInteractor = communalSceneInteractor;
        mShadeInteractor = shadeInteractor;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mLogger = logger;

        dumpManager.registerDumpable(this);
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mSleepy = mWakefulnessLifecycle.getWakefulness() == WAKEFULNESS_ASLEEP;
        mFullyDozed = mStatusBarStateController.getDozeAmount() == 1f;

        mStatusBarStateController.addCallback(mStatusBarStateControllerListener);
        mPulsing = mStatusBarStateController.isPulsing();
        mJavaAdapter.alwaysCollectFlow(mShadeAnimationInteractor.isAnyCloseAnimationRunning(),
                this::onShadeOrQsClosingChanged);
        mJavaAdapter.alwaysCollectFlow(mShadeAnimationInteractor.isLaunchingActivity(),
                this::onLaunchingActivityChanged);
        mJavaAdapter.alwaysCollectFlow(
                BooleanFlowOperators.INSTANCE.allOf(
                        mCommunalSceneInteractor.isIdleOnCommunal(),
                        BooleanFlowOperators.INSTANCE.not(mShadeInteractor.isAnyFullyExpanded())
                ),
                this::onCommunalShowingChanged);

        if (SceneContainerFlag.isEnabled()) {
            mJavaAdapter.alwaysCollectFlow(mKeyguardTransitionInteractor.transitionValue(
                            KeyguardState.LOCKSCREEN),
                    this::onLockscreenKeyguardStateTransitionValueChanged);
        }
        if (Flags.checkLockscreenGoneTransition()) {
            mJavaAdapter.alwaysCollectFlow(mKeyguardTransitionInteractor.isInTransition(
                            Edge.create(KeyguardState.LOCKSCREEN, Scenes.Gone),
                            Edge.create(KeyguardState.LOCKSCREEN, KeyguardState.GONE)),
                    this::onLockscreenInGoneTransitionChanged);
        }


        pipeline.setVisualStabilityManager(mNotifStabilityManager);
    }

    // TODO(b/203826051): Ensure stability manager can allow reordering off-screen
    //  HUNs to the top of the shade
    private final NotifStabilityManager mNotifStabilityManager =
            new NotifStabilityManager("VisualStabilityCoordinator") {
                private boolean canMoveForHeadsUp(NotificationEntry entry) {
                    if (entry == null) {
                        return false;
                    }
                    boolean isTopUnseen = NotificationMinimalism.isEnabled()
                            && (mSeenNotificationsInteractor.isTopUnseenNotification(entry)
                                || mSeenNotificationsInteractor.isTopOngoingNotification(entry));
                    if (isTopUnseen || mHeadsUpManager.isHeadsUpEntry(entry.getKey())) {
                        return !mVisibilityLocationProvider.isInVisibleLocation(entry);
                    }
                    return false;
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
        // No need to run notification pipeline when the lockscreen is in fading animation.
        mPipelineRunAllowed = !(isPanelCollapsingOrLaunchingActivity()
                || (Flags.checkLockscreenGoneTransition() && mLockscreenInGoneTransition));
        mReorderingAllowed = isReorderingAllowed();
        if (wasPipelineRunAllowed != mPipelineRunAllowed
                || wasReorderingAllowed != mReorderingAllowed) {
            mLogger.logAllowancesChanged(
                    wasPipelineRunAllowed, mPipelineRunAllowed,
                    wasReorderingAllowed, mReorderingAllowed,
                    field, value);
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
        final boolean sleepyAndDozed = mFullyDozed && mSleepy;
        final boolean stackShowing = mPanelExpanded
                || (SceneContainerFlag.isEnabled() && mLockscreenShowing);
        return (sleepyAndDozed || !stackShowing || mCommunalShowing) && !mPulsing;
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
        if (Flags.checkLockscreenGoneTransition()) {
            pw.println("  lockscreenInGoneTransition: " + mLockscreenInGoneTransition);
        }
        pw.println("reorderingAllowed: " + mReorderingAllowed);
        pw.println("  sleepy: " + mSleepy);
        pw.println("  fullyDozed: " + mFullyDozed);
        pw.println("  panelExpanded: " + mPanelExpanded);
        pw.println("  pulsing: " + mPulsing);
        pw.println("  communalShowing: " + mCommunalShowing);
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

    private void onShadeOrQsClosingChanged(boolean isClosing) {
        mNotifPanelCollapsing = isClosing;
        updateAllowedStates("notifPanelCollapsing", isClosing);
    }

    private void onLaunchingActivityChanged(boolean isLaunchingActivity) {
        mNotifPanelLaunchingActivity = isLaunchingActivity;
        updateAllowedStates("notifPanelLaunchingActivity", isLaunchingActivity);
    }

    private void onCommunalShowingChanged(boolean isShowing) {
        mCommunalShowing = isShowing;
        updateAllowedStates("communalShowing", isShowing);
    }

    private void onLockscreenKeyguardStateTransitionValueChanged(float value) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            return;
        }

        final boolean isShowing = value > 0.0f;
        if (isShowing == mLockscreenShowing) {
            return;
        }

        mLockscreenShowing = isShowing;
        updateAllowedStates("lockscreenShowing", isShowing);
    }

    private void onLockscreenInGoneTransitionChanged(boolean inGoneTransition) {
        if (!Flags.checkLockscreenGoneTransition()) {
            return;
        }
        if (inGoneTransition == mLockscreenInGoneTransition) {
            return;
        }
        mLockscreenInGoneTransition = inGoneTransition;
        updateAllowedStates("lockscreenInGoneTransition", mLockscreenInGoneTransition);
    }
}
