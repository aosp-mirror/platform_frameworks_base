/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.Notification.VISIBILITY_SECRET;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import androidx.annotation.MainThread;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen.
 */
@CoordinatorScope
public class KeyguardCoordinator implements Coordinator {
    private static final String TAG = "KeyguardCoordinator";

    private final Context mContext;
    private final Handler mMainHandler;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final HighPriorityProvider mHighPriorityProvider;

    private boolean mHideSilentNotificationsOnLockscreen;

    @Inject
    public KeyguardCoordinator(
            Context context,
            @MainThread Handler mainThreadHandler,
            KeyguardStateController keyguardStateController,
            NotificationLockscreenUserManager lockscreenUserManager,
            BroadcastDispatcher broadcastDispatcher,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            HighPriorityProvider highPriorityProvider) {
        mContext = context;
        mMainHandler = mainThreadHandler;
        mKeyguardStateController = keyguardStateController;
        mLockscreenUserManager = lockscreenUserManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHighPriorityProvider = highPriorityProvider;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        readShowSilentNotificationSetting();

        setupInvalidateNotifListCallbacks();
        pipeline.addFinalizeFilter(mNotifFilter);
    }

    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            final StatusBarNotification sbn = entry.getSbn();

            // FILTER OUT the notification when the keyguard is showing and...
            if (mKeyguardStateController.isShowing()) {
                // ... user settings or the device policy manager doesn't allow lockscreen
                // notifications;
                if (!mLockscreenUserManager.shouldShowLockscreenNotifications()) {
                    return true;
                }

                final int currUserId = mLockscreenUserManager.getCurrentUserId();
                final int notifUserId = (sbn.getUser().getIdentifier() == UserHandle.USER_ALL)
                        ? currUserId : sbn.getUser().getIdentifier();

                // ... user is in lockdown
                if (mKeyguardUpdateMonitor.isUserInLockdown(currUserId)
                        || mKeyguardUpdateMonitor.isUserInLockdown(notifUserId)) {
                    return true;
                }

                // ... device is in public mode and the user's settings doesn't allow
                // notifications to show in public mode
                if (mLockscreenUserManager.isLockscreenPublicMode(currUserId)
                        || mLockscreenUserManager.isLockscreenPublicMode(notifUserId)) {
                    if (entry.getRanking().getLockscreenVisibilityOverride() == VISIBILITY_SECRET) {
                        return true;
                    }

                    if (!mLockscreenUserManager.userAllowsNotificationsInPublic(currUserId)
                            || !mLockscreenUserManager.userAllowsNotificationsInPublic(
                            notifUserId)) {
                        return true;
                    }
                }

                // ... neither this notification nor its group have high enough priority
                // to be shown on the lockscreen
                if (entry.getParent() != null) {
                    final GroupEntry parent = entry.getParent();
                    if (priorityExceedsLockscreenShowingThreshold(parent)) {
                        return false;
                    }
                }
                return !priorityExceedsLockscreenShowingThreshold(entry);
            }
            return false;
        }
    };

    private boolean priorityExceedsLockscreenShowingThreshold(ListEntry entry) {
        if (entry == null) {
            return false;
        }
        if (mHideSilentNotificationsOnLockscreen) {
            return mHighPriorityProvider.isHighPriority(entry);
        } else {
            return entry.getRepresentativeEntry() != null
                    && !entry.getRepresentativeEntry().getRanking().isAmbient();
        }
    }

    // TODO(b/206118999): merge this class with SensitiveContentCoordinator which also depends on
    // these same updates
    private void setupInvalidateNotifListCallbacks() {
        // register onKeyguardShowing callback
        mKeyguardStateController.addCallback(mKeyguardCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);

        // register lockscreen settings changed callbacks:
        final ContentObserver settingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri.equals(Settings.Secure.getUriFor(
                        Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS))) {
                    readShowSilentNotificationSetting();
                }

                if (mKeyguardStateController.isShowing()) {
                    invalidateListFromFilter("Settings " + uri + " changed");
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS),
                false,
                settingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                false,
                settingsObserver);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS),
                false,
                settingsObserver,
                UserHandle.USER_ALL);

        // register (maybe) public mode changed callbacks:
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mBroadcastDispatcher.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mKeyguardStateController.isShowing()) {
                    // maybe public mode changed
                    invalidateListFromFilter(intent.getAction());
                }
            }}, new IntentFilter(Intent.ACTION_USER_SWITCHED));
    }

    private void invalidateListFromFilter(String reason) {
        mNotifFilter.invalidateList();
    }

    private void readShowSilentNotificationSetting() {
        mHideSilentNotificationsOnLockscreen =
                Settings.Secure.getInt(
                        mContext.getContentResolver(),
                        Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
                        1) == 0;
    }

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            invalidateListFromFilter("onUnlockedChanged");
        }

        @Override
        public void onKeyguardShowingChanged() {
            invalidateListFromFilter("onKeyguardShowingChanged");
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    // maybe public mode changed
                    invalidateListFromFilter("onStatusBarStateChanged");
                }
    };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onStrongAuthStateChanged(int userId) {
            // maybe lockdown mode changed
            invalidateListFromFilter("onStrongAuthStateChanged");
        }
    };
}
