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

import static com.android.systemui.statusbar.StatusBarState.SHADE;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.List;

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
    private final ContentResolver mContentResolver;
    private final PowerManager mPowerManager;
    private final IDreamManager mDreamManager;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final BatteryController mBatteryController;
    private final HeadsUpManager mHeadsUpManager;
    private final NotificationInterruptLogger mLogger;
    private final NotifPipelineFlags mFlags;
    private final KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;

    @VisibleForTesting
    protected boolean mUseHeadsUp = false;

    @Inject
    public NotificationInterruptStateProviderImpl(
            ContentResolver contentResolver,
            PowerManager powerManager,
            IDreamManager dreamManager,
            AmbientDisplayConfiguration ambientDisplayConfiguration,
            BatteryController batteryController,
            StatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager,
            NotificationInterruptLogger logger,
            @Main Handler mainHandler,
            NotifPipelineFlags flags,
            KeyguardNotificationVisibilityProvider keyguardNotificationVisibilityProvider) {
        mContentResolver = contentResolver;
        mPowerManager = powerManager;
        mDreamManager = dreamManager;
        mBatteryController = batteryController;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mLogger = logger;
        mFlags = flags;
        mKeyguardNotificationVisibilityProvider = keyguardNotificationVisibilityProvider;
        ContentObserver headsUpObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                boolean wasUsing = mUseHeadsUp;
                mUseHeadsUp = ENABLE_HEADS_UP
                        && Settings.Global.HEADS_UP_OFF != Settings.Global.getInt(
                        mContentResolver,
                        Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        Settings.Global.HEADS_UP_OFF);
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
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED),
                    true,
                    headsUpObserver);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(SETTING_HEADS_UP_TICKER), true,
                    headsUpObserver);
        }
        headsUpObserver.onChange(true); // set up
    }

    @Override
    public void addSuppressor(NotificationInterruptSuppressor suppressor) {
        mSuppressors.add(suppressor);
    }

    @Override
    public boolean shouldBubbleUp(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();

        if (!canAlertCommon(entry)) {
            return false;
        }

        if (!canAlertAwakeCommon(entry)) {
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
        if (mStatusBarStateController.isDozing()) {
            return shouldHeadsUpWhenDozing(entry);
        } else {
            return shouldHeadsUpWhenAwake(entry);
        }
    }

    /**
     * When an entry was added, should we launch its fullscreen intent? Examples are Alarms or
     * incoming calls.
     */
    @Override
    public boolean shouldLaunchFullScreenIntentWhenAdded(NotificationEntry entry) {
        if (entry.getSbn().getNotification().fullScreenIntent == null) {
            return false;
        }

        // Never show FSI when suppressed by DND
        if (entry.shouldSuppressFullScreenIntent()) {
            mLogger.logNoFullscreen(entry, "Suppressed by DND");
            return false;
        }

        // Never show FSI if importance is not HIGH
        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
            mLogger.logNoFullscreen(entry, "Not important enough");
            return false;
        }

        // If the notification has suppressive GroupAlertBehavior, block FSI and warn.
        StatusBarNotification sbn = entry.getSbn();
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            // b/231322873: Detect and report an event when a notification has both an FSI and a
            // suppressive groupAlertBehavior, and now correctly block the FSI from firing.
            final int uid = entry.getSbn().getUid();
            android.util.EventLog.writeEvent(0x534e4554, "231322873", uid, "groupAlertBehavior");
            mLogger.logNoFullscreenWarning(entry, "GroupAlertBehavior will prevent HUN");
            return false;
        }

        // If the screen is off, then launch the FullScreenIntent
        if (!mPowerManager.isInteractive()) {
            mLogger.logFullscreen(entry, "Device is not interactive");
            return true;
        }

        // If the device is currently dreaming, then launch the FullScreenIntent
        if (isDreaming()) {
            mLogger.logFullscreen(entry, "Device is dreaming");
            return true;
        }

        // If the keyguard is showing, then launch the FullScreenIntent
        if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
            mLogger.logFullscreen(entry, "Keyguard is showing");
            return true;
        }

        // If the notification should HUN, then we don't need FSI
        if (shouldHeadsUp(entry)) {
            mLogger.logNoFullscreen(entry, "Expected to HUN");
            return false;
        }

        // Check whether FSI requires the keyguard to be showing.
        if (mFlags.fullScreenIntentRequiresKeyguard()) {

            // If notification won't HUN and keyguard is showing, launch the FSI.
            if (mKeyguardStateController.isShowing()) {
                if (mKeyguardStateController.isOccluded()) {
                    mLogger.logFullscreen(entry, "Expected not to HUN while keyguard occluded");
                } else {
                    // Likely LOCKED_SHADE, but launch FSI anyway
                    mLogger.logFullscreen(entry, "Keyguard is showing and not occluded");
                }
                return true;
            }

            // Detect the case determined by b/231322873 to launch FSI while device is in use,
            // as blocked by the correct implementation, and report the event.
            final int uid = entry.getSbn().getUid();
            android.util.EventLog.writeEvent(0x534e4554, "231322873", uid, "no hun or keyguard");
            mLogger.logNoFullscreenWarning(entry, "Expected not to HUN while not on keyguard");
            return false;
        }

        // If the notification won't HUN for some other reason (DND/snooze/etc), launch FSI.
        mLogger.logFullscreen(entry, "Expected not to HUN");
        return true;
    }

    private boolean isDreaming() {
        try {
            return mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
            return false;
        }
    }

    private boolean shouldHeadsUpWhenAwake(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();

        if (!mUseHeadsUp) {
            mLogger.logNoHeadsUpFeatureDisabled();
            return false;
        }

        if (!canAlertCommon(entry)) {
            return false;
        }

        if (!canAlertHeadsUpCommon(entry)) {
            return false;
        }

        if (!canAlertAwakeCommon(entry)) {
            return false;
        }

        if (isSnoozedPackage(sbn)) {
            mLogger.logNoHeadsUpPackageSnoozed(entry);
            return false;
        }

        boolean inShade = mStatusBarStateController.getState() == SHADE;
        if (entry.isBubble() && inShade) {
            mLogger.logNoHeadsUpAlreadyBubbled(entry);
            return false;
        }

        if (entry.shouldSuppressPeek()) {
            mLogger.logNoHeadsUpSuppressedByDnd(entry);
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
            mLogger.logNoHeadsUpNotImportant(entry);
            return false;
        }

        boolean inUse = mPowerManager.isScreenOn() && !isDreaming();

        if (!inUse) {
            mLogger.logNoHeadsUpNotInUse(entry);
            return false;
        }

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressAwakeHeadsUp(entry)) {
                mLogger.logNoHeadsUpSuppressedBy(entry, mSuppressors.get(i));
                return false;
            }
        }
        mLogger.logHeadsUp(entry);
        return true;
    }

    /**
     * Whether or not the notification should "pulse" on the user's display when the phone is
     * dozing.  This displays the ambient view of the notification.
     *
     * @param entry the entry to check
     * @return true if the entry should ambient pulse, false otherwise
     */
    private boolean shouldHeadsUpWhenDozing(NotificationEntry entry) {
        if (!mAmbientDisplayConfiguration.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) {
            mLogger.logNoPulsingSettingDisabled(entry);
            return false;
        }

        if (mBatteryController.isAodPowerSave()) {
            mLogger.logNoPulsingBatteryDisabled(entry);
            return false;
        }

        if (!canAlertCommon(entry)) {
            mLogger.logNoPulsingNoAlert(entry);
            return false;
        }

        if (!canAlertHeadsUpCommon(entry)) {
            mLogger.logNoPulsingNoAlert(entry);
            return false;
        }

        if (entry.shouldSuppressAmbient()) {
            mLogger.logNoPulsingNoAmbientEffect(entry);
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            mLogger.logNoPulsingNotImportant(entry);
            return false;
        }
        mLogger.logPulsing(entry);
        return true;
    }

    /**
     * Common checks between regular & AOD heads up and bubbles.
     *
     * @param entry the entry to check
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertCommon(NotificationEntry entry) {
        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressInterruptions(entry)) {
                mLogger.logNoAlertingSuppressedBy(entry, mSuppressors.get(i), /* awake */ false);
                return false;
            }
        }

        if (mKeyguardNotificationVisibilityProvider.shouldHideNotification(entry)) {
            mLogger.keyguardHideNotification(entry);
            return false;
        }

        return true;
    }

    /**
     * Common checks for heads up notifications on regular and AOD displays.
     *
     * @param entry the entry to check
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertHeadsUpCommon(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();

        // Don't alert notifications that are suppressed due to group alert behavior
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            mLogger.logNoAlertingGroupAlertBehavior(entry);
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            mLogger.logNoAlertingRecentFullscreen(entry);
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
    private boolean canAlertAwakeCommon(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressAwakeInterruptions(entry)) {
                mLogger.logNoAlertingSuppressedBy(entry, mSuppressors.get(i), /* awake */ true);
                return false;
            }
        }
        return true;
    }

    private boolean isSnoozedPackage(StatusBarNotification sbn) {
        return mHeadsUpManager.isSnoozed(sbn.getPackageName());
    }
}
