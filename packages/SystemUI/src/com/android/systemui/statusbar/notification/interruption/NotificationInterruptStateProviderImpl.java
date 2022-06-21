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
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

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
    private final NotificationFilter mNotificationFilter;
    private final ContentResolver mContentResolver;
    private final PowerManager mPowerManager;
    private final IDreamManager mDreamManager;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final BatteryController mBatteryController;
    private final ContentObserver mHeadsUpObserver;
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
            NotificationFilter notificationFilter,
            BatteryController batteryController,
            StatusBarStateController statusBarStateController,
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
        mNotificationFilter = notificationFilter;
        mStatusBarStateController = statusBarStateController;
        mHeadsUpManager = headsUpManager;
        mLogger = logger;
        mFlags = flags;
        mKeyguardNotificationVisibilityProvider = keyguardNotificationVisibilityProvider;
        mHeadsUpObserver = new ContentObserver(mainHandler) {
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
                    mHeadsUpObserver);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(SETTING_HEADS_UP_TICKER), true,
                    mHeadsUpObserver);
        }
        mHeadsUpObserver.onChange(true); // set up
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
        return entry.getSbn().getNotification().fullScreenIntent != null
                && (!shouldHeadsUp(entry)
                || mStatusBarStateController.getState() == StatusBarState.KEYGUARD);
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

        boolean isDreaming = false;
        try {
            isDreaming = mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
        }
        boolean inUse = mPowerManager.isScreenOn() && !isDreaming;

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
        if (!mFlags.isNewPipelineEnabled() && mNotificationFilter.shouldFilterOut(entry)) {
            mLogger.logNoAlertingFilteredOut(entry);
            return false;
        }

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
