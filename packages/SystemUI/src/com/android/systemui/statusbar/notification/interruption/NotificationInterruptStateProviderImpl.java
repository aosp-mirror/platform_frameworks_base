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

package com.android.systemui.statusbar.notification.interruption;

import static android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED;
import static android.provider.Settings.Global.HEADS_UP_OFF;

import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD;
import static com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA;
import static com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR;
import static com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI;

import android.app.Notification;
import android.app.NotificationManager;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.EventLog;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Provides heads-up and pulsing state for notification entries.
 */
@SysUISingleton
public class NotificationInterruptStateProviderImpl implements NotificationInterruptStateProvider {
    private static final String TAG = "InterruptionStateProvider";
    private static final boolean ENABLE_HEADS_UP = true;
    private static final String SETTING_HEADS_UP_TICKER = "ticker_gets_heads_up";

    private final List<NotificationInterruptSuppressor> mSuppressors = new ArrayList<>();
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final PowerManager mPowerManager;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final BatteryController mBatteryController;
    private final HeadsUpManager mHeadsUpManager;
    private final NotificationInterruptLogger mLogger;
    private final NotifPipelineFlags mFlags;
    private final KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;
    private final UiEventLogger mUiEventLogger;
    private final UserTracker mUserTracker;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final SystemClock mSystemClock;
    private final GlobalSettings mGlobalSettings;
    private final EventLog mEventLog;
    private final Optional<Bubbles> mBubbles;

    @VisibleForTesting
    protected boolean mUseHeadsUp = false;

    public enum NotificationInterruptEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "FSI suppressed for suppressive GroupAlertBehavior")
        FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR(1235),

        @UiEvent(doc = "FSI suppressed for suppressive BubbleMetadata")
        FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA(1353),

        @UiEvent(doc = "FSI suppressed for requiring neither HUN nor keyguard")
        FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD(1236),

        @UiEvent(doc = "HUN suppressed for old when")
        HUN_SUPPRESSED_OLD_WHEN(1237),

        @UiEvent(doc = "HUN snooze bypassed for potentially suppressed FSI")
        HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI(1269);

        private final int mId;

        NotificationInterruptEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    @Inject
    public NotificationInterruptStateProviderImpl(
            PowerManager powerManager,
            AmbientDisplayConfiguration ambientDisplayConfiguration,
            BatteryController batteryController,
            StatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager,
            NotificationInterruptLogger logger,
            @Main Handler mainHandler,
            NotifPipelineFlags flags,
            KeyguardNotificationVisibilityProvider keyguardNotificationVisibilityProvider,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            DeviceProvisionedController deviceProvisionedController,
            SystemClock systemClock,
            GlobalSettings globalSettings,
            EventLog eventLog,
            Optional<Bubbles> bubbles) {
        mPowerManager = powerManager;
        mBatteryController = batteryController;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mLogger = logger;
        mFlags = flags;
        mKeyguardNotificationVisibilityProvider = keyguardNotificationVisibilityProvider;
        mUiEventLogger = uiEventLogger;
        mUserTracker = userTracker;
        mDeviceProvisionedController = deviceProvisionedController;
        mSystemClock = systemClock;
        mGlobalSettings = globalSettings;
        mEventLog = eventLog;
        mBubbles = bubbles;
        ContentObserver headsUpObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean wasUsing = mUseHeadsUp;
                final boolean settingEnabled = HEADS_UP_OFF
                        != mGlobalSettings.getInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_OFF);
                mUseHeadsUp = ENABLE_HEADS_UP && settingEnabled;
                mLogger.logHeadsUpFeatureChanged(mUseHeadsUp);
                if (wasUsing != mUseHeadsUp) {
                    if (!mUseHeadsUp) {
                        mLogger.logWillDismissAll();
                        mHeadsUpManager.releaseAllImmediately();
                    }
                }
            }
        };

        if (ENABLE_HEADS_UP) {
            mGlobalSettings.registerContentObserver(
                    mGlobalSettings.getUriFor(HEADS_UP_NOTIFICATIONS_ENABLED),
                    true,
                    headsUpObserver);
            mGlobalSettings.registerContentObserver(
                    mGlobalSettings.getUriFor(SETTING_HEADS_UP_TICKER), true,
                    headsUpObserver);
        }
        headsUpObserver.onChange(true); // set up
    }

    @Override
    public void addSuppressor(NotificationInterruptSuppressor suppressor) {
        mSuppressors.add(suppressor);
    }

    @Override
    public void removeSuppressor(NotificationInterruptSuppressor suppressor) {
        mSuppressors.remove(suppressor);
    }

    @Override
    public boolean shouldBubbleUp(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();

        if (!canAlertCommon(entry, false)) {
            return false;
        }

        if (!canAlertAwakeCommon(entry, false)) {
            return false;
        }

        if (!entry.canBubble()) {
            mLogger.logNoBubbleNotAllowed(entry);
            return false;
        }

        if (entry.getBubbleMetadata() == null
                || (entry.getBubbleMetadata().getShortcutId() == null
                    && entry.getBubbleMetadata().getIntent() == null)) {
            mLogger.logNoBubbleNoMetadata(entry);
            return false;
        }

        return true;
    }


    @Override
    public boolean shouldHeadsUp(NotificationEntry entry) {
        return checkHeadsUp(entry, true);
    }

    @Override
    public boolean checkHeadsUp(NotificationEntry entry, boolean log) {
        if (mStatusBarStateController.isDozing()) {
            return shouldHeadsUpWhenDozing(entry, log);
        } else {
            return shouldHeadsUpWhenAwake(entry, log);
        }
    }

    /**
     * When an entry was added, should we launch its fullscreen intent? Examples are Alarms or
     * incoming calls.
     */
    @Override
    public boolean shouldLaunchFullScreenIntentWhenAdded(NotificationEntry entry) {
        FullScreenIntentDecision decision = getFullScreenIntentDecision(entry);
        logFullScreenIntentDecision(entry, decision);
        return decision.shouldLaunch;
    }

    // Given whether the relevant entry was suppressed by DND, and the full screen intent launch
    // decision independent of the DND decision, returns the combined FullScreenIntentDecision that
    // results. If the entry was suppressed by DND but the decision otherwise would launch the
    // FSI, then it is suppressed *only* by DND, whereas (because the DND decision happens before
    // all others) if the entry would not otherwise have launched the FSI, DND is the effective
    // suppressor.
    //
    // If the entry was not suppressed by DND, just returns the given decision.
    @NonNull
    private FullScreenIntentDecision getDecisionGivenSuppression(FullScreenIntentDecision decision,
            boolean suppressedByDND) {
        if (suppressedByDND) {
            return decision.shouldLaunch
                    ? FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
                    : FullScreenIntentDecision.NO_FSI_SUPPRESSED_BY_DND;
        }
        return decision;
    }

    @Override
    public FullScreenIntentDecision getFullScreenIntentDecision(@NonNull NotificationEntry entry) {
        if (entry.getSbn().getNotification().fullScreenIntent == null) {
            if (entry.isStickyAndNotDemoted()) {
                return FullScreenIntentDecision.NO_FSI_SHOW_STICKY_HUN;
            }
            return FullScreenIntentDecision.NO_FULL_SCREEN_INTENT;
        }

        // Boolean indicating whether this FSI would have been suppressed by DND. Because we
        // want to be able to identify when something would have shown an FSI if not for being
        // suppressed, we need to keep track of this value for future decisions.
        boolean suppressedByDND = false;

        // Never show FSI when suppressed by DND
        if (entry.shouldSuppressFullScreenIntent()) {
            suppressedByDND = true;
        }

        // Never show FSI if importance is not HIGH
        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.NO_FSI_NOT_IMPORTANT_ENOUGH,
                    suppressedByDND);
        }

        // If the notification has suppressive GroupAlertBehavior, block FSI and warn.
        StatusBarNotification sbn = entry.getSbn();
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            // b/231322873: Detect and report an event when a notification has both an FSI and a
            // suppressive groupAlertBehavior, and now correctly block the FSI from firing.
            return getDecisionGivenSuppression(
                    FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR,
                    suppressedByDND);
        }

        // If the notification has suppressive BubbleMetadata, block FSI and warn.
        Notification.BubbleMetadata bubbleMetadata = sbn.getNotification().getBubbleMetadata();
        if (bubbleMetadata != null && bubbleMetadata.isNotificationSuppressed()) {
            // b/274759612: Detect and report an event when a notification has both an FSI and a
            // suppressive BubbleMetadata, and now correctly block the FSI from firing.
            return getDecisionGivenSuppression(
                    FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_BUBBLE_METADATA,
                    suppressedByDND);
        }

        // Notification is coming from a suspended package, block FSI
        if (entry.getRanking().isSuspended()) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.NO_FSI_SUSPENDED,
                    suppressedByDND);
        }

        // If the screen is off, then launch the FullScreenIntent
        if (!mPowerManager.isInteractive()) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE,
                    suppressedByDND);
        }

        // If the device is currently dreaming, then launch the FullScreenIntent
        // We avoid using IDreamManager#isDreaming here as that method will return false during
        // the dream's wake-up phase.
        if (mStatusBarStateController.isDreaming()) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_DEVICE_IS_DREAMING,
                    suppressedByDND);
        }

        // If the keyguard is showing, then launch the FullScreenIntent
        if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_KEYGUARD_SHOWING,
                    suppressedByDND);
        }

        // If the notification should HUN, then we don't need FSI
        // Because this is not the heads-up decision-making point, and checking whether it would
        // HUN, don't log this specific check.
        if (checkHeadsUp(entry, /* log= */ false)) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.NO_FSI_EXPECTED_TO_HUN,
                    suppressedByDND);
        }

        // If notification won't HUN and keyguard is showing, launch the FSI.
        if (mKeyguardStateController.isShowing()) {
            if (mKeyguardStateController.isOccluded()) {
                return getDecisionGivenSuppression(
                        FullScreenIntentDecision.FSI_KEYGUARD_OCCLUDED,
                        suppressedByDND);
            } else {
                // Likely LOCKED_SHADE, but launch FSI anyway
                return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_LOCKED_SHADE,
                        suppressedByDND);
            }
        }

        // The device is not provisioned, launch FSI.
        if (!mDeviceProvisionedController.isDeviceProvisioned()) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_NOT_PROVISIONED,
                    suppressedByDND);
        }

        // The current user hasn't completed setup, launch FSI.
        if (!mDeviceProvisionedController.isCurrentUserSetup()) {
            return getDecisionGivenSuppression(FullScreenIntentDecision.FSI_USER_SETUP_INCOMPLETE,
                    suppressedByDND);
        }

        // Detect the case determined by b/231322873 to launch FSI while device is in use,
        // as blocked by the correct implementation, and report the event.
        return getDecisionGivenSuppression(FullScreenIntentDecision.NO_FSI_NO_HUN_OR_KEYGUARD,
                suppressedByDND);
    }

    @Override
    public void logFullScreenIntentDecision(NotificationEntry entry,
            FullScreenIntentDecision decision) {
        final int uid = entry.getSbn().getUid();
        final String packageName = entry.getSbn().getPackageName();
        switch (decision) {
            case NO_FULL_SCREEN_INTENT:
                // explicitly prevent logging for this (frequent) case
                return;
            case NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR:
                mEventLog.writeEvent(0x534e4554, "231322873", uid,
                        "groupAlertBehavior");
                mUiEventLogger.log(FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR, uid,
                        packageName);
                mLogger.logNoFullscreenWarning(entry,
                        decision + ": GroupAlertBehavior will prevent HUN");
                return;
            case NO_FSI_SUPPRESSIVE_BUBBLE_METADATA:
                mEventLog.writeEvent(0x534e4554, "274759612", uid,
                        "bubbleMetadata");
                mUiEventLogger.log(FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA, uid,
                        packageName);
                mLogger.logNoFullscreenWarning(entry,
                        decision + ": BubbleMetadata may prevent HUN");
                return;
            case NO_FSI_NO_HUN_OR_KEYGUARD:
                mEventLog.writeEvent(0x534e4554, "231322873", uid,
                        "no hun or keyguard");
                mUiEventLogger.log(FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD, uid, packageName);
                mLogger.logNoFullscreenWarning(entry,
                        decision + ": Expected not to HUN while not on keyguard");
                return;
            default:
                if (decision.shouldLaunch) {
                    mLogger.logFullscreen(entry, decision.name());
                } else {
                    mLogger.logNoFullscreen(entry, decision.name());
                }
        }
    }
    private boolean shouldHeadsUpWhenAwake(NotificationEntry entry, boolean log) {
        StatusBarNotification sbn = entry.getSbn();

        if (!mUseHeadsUp) {
            if (log) mLogger.logNoHeadsUpFeatureDisabled();
            return false;
        }

        if (!canAlertCommon(entry, log)) {
            return false;
        }

        if (!canAlertHeadsUpCommon(entry, log)) {
            return false;
        }

        if (!canAlertAwakeCommon(entry, log)) {
            return false;
        }

        final boolean isSnoozedPackage = isSnoozedPackage(sbn);
        final boolean hasFsi = sbn.getNotification().fullScreenIntent != null;

        // Assume any notification with an FSI is time-sensitive (like an alarm or incoming call)
        // and ignore whether HUNs have been snoozed for the package.
        if (isSnoozedPackage && !hasFsi) {
            if (log) mLogger.logNoHeadsUpPackageSnoozed(entry);
            return false;
        }

        boolean inShade = mStatusBarStateController.getState() == SHADE;
        boolean bubblesCanShowNotification =
                mBubbles.isPresent() && mBubbles.get().canShowBubbleNotification();
        if (entry.isBubble() && inShade && bubblesCanShowNotification) {
            if (log) mLogger.logNoHeadsUpAlreadyBubbled(entry);
            return false;
        }

        if (entry.shouldSuppressPeek()) {
            if (log) mLogger.logNoHeadsUpSuppressedByDnd(entry);
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
            if (log) mLogger.logNoHeadsUpNotImportant(entry);
            return false;
        }

        boolean inUse = mPowerManager.isScreenOn() && !mStatusBarStateController.isDreaming();

        if (!inUse) {
            if (log) mLogger.logNoHeadsUpNotInUse(entry);
            return false;
        }

        if (shouldSuppressHeadsUpWhenAwakeForOldWhen(entry, log)) {
            return false;
        }

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressAwakeHeadsUp(entry)) {
                if (log) mLogger.logNoHeadsUpSuppressedBy(entry, mSuppressors.get(i));
                return false;
            }
        }

        if (isSnoozedPackage) {
            if (log) {
                mLogger.logHeadsUpPackageSnoozeBypassedHasFsi(entry);
                final int uid = entry.getSbn().getUid();
                final String packageName = entry.getSbn().getPackageName();
                mUiEventLogger.log(HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI, uid,
                        packageName);
            }

            return true;
        }

        if (log) mLogger.logHeadsUp(entry);
        return true;
    }

    /**
     * Whether or not the notification should "pulse" on the user's display when the phone is
     * dozing.  This displays the ambient view of the notification.
     *
     * @param entry the entry to check
     * @return true if the entry should ambient pulse, false otherwise
     */
    private boolean shouldHeadsUpWhenDozing(NotificationEntry entry, boolean log) {
        if (!mAmbientDisplayConfiguration.pulseOnNotificationEnabled(mUserTracker.getUserId())) {
            if (log) mLogger.logNoPulsingSettingDisabled(entry);
            return false;
        }

        if (mBatteryController.isAodPowerSave()) {
            if (log) mLogger.logNoPulsingBatteryDisabled(entry);
            return false;
        }

        if (!canAlertCommon(entry, log)) {
            if (log) mLogger.logNoPulsingNoAlert(entry);
            return false;
        }

        if (!canAlertHeadsUpCommon(entry, log)) {
            if (log) mLogger.logNoPulsingNoAlert(entry);
            return false;
        }

        if (entry.shouldSuppressAmbient()) {
            if (log) mLogger.logNoPulsingNoAmbientEffect(entry);
            return false;
        }

        if (entry.getRanking().getLockscreenVisibilityOverride()
                == Notification.VISIBILITY_PRIVATE) {
            if (log) mLogger.logNoPulsingNotificationHiddenOverride(entry);
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            if (log) mLogger.logNoPulsingNotImportant(entry);
            return false;
        }
        if (log) mLogger.logPulsing(entry);
        return true;
    }

    /**
     * Common checks between regular & AOD heads up and bubbles.
     *
     * @param entry the entry to check
     * @param log whether or not to log the results of these checks
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertCommon(NotificationEntry entry, boolean log) {
        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressInterruptions(entry)) {
                if (log) {
                    mLogger.logNoAlertingSuppressedBy(entry, mSuppressors.get(i),
                            /* awake */ false);
                }
                return false;
            }
        }

        if (entry.getRanking().isSuspended()) {
            if (log) {
                mLogger.logNoAlertingAppSuspended(entry);
            }
            return false;
        }

        if (mKeyguardNotificationVisibilityProvider.shouldHideNotification(entry)) {
            if (log) mLogger.logNoAlertingNotificationHidden(entry);
            return false;
        }

        return true;
    }

    /**
     * Common checks for heads up notifications on regular and AOD displays.
     *
     * @param entry the entry to check
     * @param log whether or not to log the results of these checks
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertHeadsUpCommon(NotificationEntry entry, boolean log) {
        StatusBarNotification sbn = entry.getSbn();

        // Don't alert notifications that are suppressed due to group alert behavior
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            if (log) mLogger.logNoAlertingGroupAlertBehavior(entry);
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            if (log) mLogger.logNoAlertingRecentFullscreen(entry);
            return false;
        }

        return true;
    }

    /**
     * Common checks between alerts that occur while the device is awake (heads up & bubbles).
     *
     * @param entry the entry to check
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertAwakeCommon(NotificationEntry entry, boolean log) {
        StatusBarNotification sbn = entry.getSbn();

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressAwakeInterruptions(entry)) {
                if (log) {
                    mLogger.logNoAlertingSuppressedBy(entry, mSuppressors.get(i), /* awake */ true);
                }
                return false;
            }
        }
        return true;
    }

    private boolean isSnoozedPackage(StatusBarNotification sbn) {
        return mHeadsUpManager.isSnoozed(sbn.getPackageName());
    }

    private boolean shouldSuppressHeadsUpWhenAwakeForOldWhen(NotificationEntry entry, boolean log) {
        final Notification notification = entry.getSbn().getNotification();
        if (notification == null) {
            return false;
        }

        final long when = notification.getWhen();
        final long now = mSystemClock.currentTimeMillis();
        final long age = now - when;

        if (age < MAX_HUN_WHEN_AGE_MS) {
            return false;
        }

        if (when <= 0) {
            // Some notifications (including many system notifications) are posted with the "when"
            // field set to 0. Nothing in the Javadocs for Notification mentions a special meaning
            // for a "when" of 0, but Android didn't even exist at the dawn of the Unix epoch.
            // Therefore, assume that these notifications effectively don't have a "when" value,
            // and don't suppress HUNs.
            if (log) mLogger.logMaybeHeadsUpDespiteOldWhen(entry, when, age, "when <= 0");
            return false;
        }

        if (notification.fullScreenIntent != null) {
            if (log) mLogger.logMaybeHeadsUpDespiteOldWhen(entry, when, age, "full-screen intent");
            return false;
        }

        if (notification.isForegroundService()) {
            if (log) mLogger.logMaybeHeadsUpDespiteOldWhen(entry, when, age, "foreground service");
            return false;
        }

        if (notification.isUserInitiatedJob()) {
            if (log) mLogger.logMaybeHeadsUpDespiteOldWhen(entry, when, age, "user initiated job");
            return false;
        }

        if (log) mLogger.logNoHeadsUpOldWhen(entry, when, age);
        final int uid = entry.getSbn().getUid();
        final String packageName = entry.getSbn().getPackageName();
        mUiEventLogger.log(NotificationInterruptEvent.HUN_SUPPRESSED_OLD_WHEN, uid, packageName);
        return true;
    }

    public static final long MAX_HUN_WHEN_AGE_MS = 24 * 60 * 60 * 1000;
}
