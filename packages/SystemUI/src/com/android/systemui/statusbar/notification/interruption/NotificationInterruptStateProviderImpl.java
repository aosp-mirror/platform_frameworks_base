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
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides heads-up and pulsing state for notification entries.
 */
@Singleton
public class NotificationInterruptStateProviderImpl implements NotificationInterruptStateProvider {
    private static final String TAG = "InterruptionStateProvider";
    private static final boolean DEBUG = true; //false;
    private static final boolean DEBUG_HEADS_UP = true;
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
    private HeadsUpManager mHeadsUpManager;

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
            @Main Handler mainHandler) {
        mContentResolver = contentResolver;
        mPowerManager = powerManager;
        mDreamManager = dreamManager;
        mBatteryController = batteryController;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mNotificationFilter = notificationFilter;
        mStatusBarStateController = statusBarStateController;
        mHeadsUpManager = headsUpManager;
        mHeadsUpObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                boolean wasUsing = mUseHeadsUp;
                mUseHeadsUp = ENABLE_HEADS_UP
                        && Settings.Global.HEADS_UP_OFF != Settings.Global.getInt(
                        mContentResolver,
                        Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        Settings.Global.HEADS_UP_OFF);
                Log.d(TAG, "heads up is " + (mUseHeadsUp ? "enabled" : "disabled"));
                if (wasUsing != mUseHeadsUp) {
                    if (!mUseHeadsUp) {
                        Log.d(TAG, "dismissing any existing heads up notification on "
                                + "disable event");
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
            if (DEBUG) {
                Log.d(TAG, "No bubble up: not allowed to bubble: " + sbn.getKey());
            }
            return false;
        }

        if (!entry.isBubble()) {
            if (DEBUG) {
                Log.d(TAG, "No bubble up: notification " + sbn.getKey()
                        + " is bubble? " + entry.isBubble());
            }
            return false;
        }

        if (entry.getBubbleMetadata() == null
                || (entry.getBubbleMetadata().getShortcutId() == null
                    && entry.getBubbleMetadata().getBubbleIntent() == null)) {
            if (DEBUG) {
                Log.d(TAG, "No bubble up: notification: " + sbn.getKey()
                        + " doesn't have valid metadata");
            }
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
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No heads up: no huns");
            }
            return false;
        }

        if (!canAlertCommon(entry)) {
            return false;
        }

        if (!canAlertAwakeCommon(entry)) {
            return false;
        }

        boolean inShade = mStatusBarStateController.getState() == SHADE;
        if (entry.isBubble() && inShade) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No heads up: in unlocked shade where notification is shown as a "
                        + "bubble: " + sbn.getKey());
            }
            return false;
        }

        if (entry.shouldSuppressPeek()) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No heads up: suppressed by DND: " + sbn.getKey());
            }
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No heads up: unimportant notification: " + sbn.getKey());
            }
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
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No heads up: not in use: " + sbn.getKey());
            }
            return false;
        }

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressAwakeHeadsUp(entry)) {
                if (DEBUG_HEADS_UP) {
                    Log.d(TAG, "No heads up: aborted by suppressor: "
                            + mSuppressors.get(i).getName() + " sbnKey=" + sbn.getKey());
                }
                return false;
            }
        }
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
        StatusBarNotification sbn = entry.getSbn();

        if (!mAmbientDisplayConfiguration.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No pulsing: disabled by setting: " + sbn.getKey());
            }
            return false;
        }

        if (mBatteryController.isAodPowerSave()) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No pulsing: disabled by battery saver: " + sbn.getKey());
            }
            return false;
        }

        if (!canAlertCommon(entry)) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No pulsing: notification shouldn't alert: " + sbn.getKey());
            }
            return false;
        }

        if (entry.shouldSuppressAmbient()) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No pulsing: ambient effect suppressed: " + sbn.getKey());
            }
            return false;
        }

        if (entry.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No pulsing: not important enough: " + sbn.getKey());
            }
            return false;
        }
        return true;
    }

    /**
     * Common checks between regular & AOD heads up and bubbles.
     *
     * @param entry the entry to check
     * @return true if these checks pass, false if the notification should not alert
     */
    private boolean canAlertCommon(NotificationEntry entry) {
        StatusBarNotification sbn = entry.getSbn();

        if (mNotificationFilter.shouldFilterOut(entry)) {
            if (DEBUG || DEBUG_HEADS_UP) {
                Log.d(TAG, "No alerting: filtered notification: " + sbn.getKey());
            }
            return false;
        }

        // Don't alert notifications that are suppressed due to group alert behavior
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            if (DEBUG || DEBUG_HEADS_UP) {
                Log.d(TAG, "No alerting: suppressed due to group alert behavior");
            }
            return false;
        }

        for (int i = 0; i < mSuppressors.size(); i++) {
            if (mSuppressors.get(i).suppressInterruptions(entry)) {
                if (DEBUG_HEADS_UP) {
                    Log.d(TAG, "No alerting: aborted by suppressor: "
                            + mSuppressors.get(i).getName() + " sbnKey=" + sbn.getKey());
                }
                return false;
            }
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
                if (DEBUG_HEADS_UP) {
                    Log.d(TAG, "No alerting: aborted by suppressor: "
                            + mSuppressors.get(i).getName() + " sbnKey=" + sbn.getKey());
                }
                return false;
            }
        }

        if (isSnoozedPackage(sbn)) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No alerting: snoozed package: " + sbn.getKey());
            }
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            if (DEBUG_HEADS_UP) {
                Log.d(TAG, "No alerting: recent fullscreen: " + sbn.getKey());
            }
            return false;
        }

        return true;
    }

    private boolean isSnoozedPackage(StatusBarNotification sbn) {
        return mHeadsUpManager.isSnoozed(sbn.getPackageName());
    }
}
