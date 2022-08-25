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

package com.android.server.accessibility.magnification;

import static android.provider.Settings.Secure.ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE;

import android.Manifest;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;

/**
 * A class to show notification to prompt the user that this feature is available.
 */
public class WindowMagnificationPromptController {

    private static final Uri MAGNIFICATION_WINDOW_MODE_PROMPT_URI = Settings.Secure.getUriFor(
            ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT);
    @VisibleForTesting
    static final String ACTION_DISMISS =
            "com.android.server.accessibility.magnification.action.DISMISS";
    @VisibleForTesting
    static final String ACTION_TURN_ON_IN_SETTINGS =
            "com.android.server.accessibility.magnification.action.TURN_ON_IN_SETTINGS";
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final ContentObserver mContentObserver;
    private final int mUserId;
    @VisibleForTesting
    BroadcastReceiver mNotificationActionReceiver;

    private boolean mNeedToShowNotification;

    @MainThread
    public WindowMagnificationPromptController(@NonNull Context context, int userId) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mUserId = userId;
        mContentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                onPromptSettingsValueChanged();
            }
        };
        context.getContentResolver().registerContentObserver(MAGNIFICATION_WINDOW_MODE_PROMPT_URI,
                false, mContentObserver, mUserId);
        mNeedToShowNotification = isWindowMagnificationPromptEnabled();
    }

    @VisibleForTesting
    protected void onPromptSettingsValueChanged() {
        final boolean needToShowNotification = isWindowMagnificationPromptEnabled();
        if (mNeedToShowNotification == needToShowNotification) {
            return;
        }
        mNeedToShowNotification = needToShowNotification;
        if (!mNeedToShowNotification) {
            unregisterReceiverIfNeeded();
            mNotificationManager.cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
        }
    }

    /**
     * Shows the prompt notification that could bring users to magnification settings if necessary.
     */
    @MainThread
    void showNotificationIfNeeded() {
        if (!mNeedToShowNotification) return;

        final Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                SystemNotificationChannels.ACCESSIBILITY_MAGNIFICATION);
        final String message = mContext.getString(R.string.window_magnification_prompt_content);

        notificationBuilder.setSmallIcon(R.drawable.ic_accessibility_24dp)
                .setContentTitle(mContext.getString(R.string.window_magnification_prompt_title))
                .setContentText(message)
                .setLargeIcon(Icon.createWithResource(mContext,
                        R.drawable.ic_accessibility_magnification))
                .setTicker(mContext.getString(R.string.window_magnification_prompt_title))
                .setOnlyAlertOnce(true)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setDeleteIntent(createPendingIntent(ACTION_DISMISS))
                .setContentIntent(createPendingIntent(ACTION_TURN_ON_IN_SETTINGS))
                .setActions(buildTurnOnAction());
        mNotificationManager.notify(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE,
                notificationBuilder.build());
        registerReceiverIfNeeded();
    }

    /**
     * Called when this object is not used anymore to release resources if necessary.
     */
    @VisibleForTesting
    @MainThread
    public void onDestroy() {
        dismissNotification();
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }

    private boolean isWindowMagnificationPromptEnabled() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT, 0, mUserId) == 1;
    }

    private Notification.Action buildTurnOnAction() {
        return new Notification.Action.Builder(null,
                mContext.getString(R.string.turn_on_magnification_settings_action),
                createPendingIntent(ACTION_TURN_ON_IN_SETTINGS)).build();
    }

    private PendingIntent createPendingIntent(String action) {
        final Intent intent = new Intent(action);
        intent.setPackage(mContext.getPackageName());
        return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private void registerReceiverIfNeeded() {
        if (mNotificationActionReceiver != null) {
            return;
        }
        mNotificationActionReceiver = new NotificationActionReceiver();
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_DISMISS);
        intentFilter.addAction(ACTION_TURN_ON_IN_SETTINGS);
        mContext.registerReceiver(mNotificationActionReceiver, intentFilter,
                Manifest.permission.MANAGE_ACCESSIBILITY, null, Context.RECEIVER_EXPORTED);
    }

    private void launchMagnificationSettings() {
        final Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME,
                MAGNIFICATION_COMPONENT_NAME.flattenToShortString());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Bundle bundle = ActivityOptions.makeBasic().setLaunchDisplayId(
                mContext.getDisplayId()).toBundle();
        mContext.startActivityAsUser(intent, bundle, UserHandle.of(mUserId));
        mContext.getSystemService(StatusBarManager.class).collapsePanels();
    }

    private void dismissNotification() {
        unregisterReceiverIfNeeded();
        mNotificationManager.cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
    }

    private void unregisterReceiverIfNeeded() {
        if (mNotificationActionReceiver == null) {
            return;
        }
        mContext.unregisterReceiver(mNotificationActionReceiver);
        mNotificationActionReceiver = null;
    }

    private class NotificationActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TextUtils.isEmpty(action)) return;

            mNeedToShowNotification = false;
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT, 0, mUserId);

            if (ACTION_TURN_ON_IN_SETTINGS.equals(action)) {
                launchMagnificationSettings();
                dismissNotification();
            } else if (ACTION_DISMISS.equals(action)) {
                dismissNotification();
            }
        }
    }
}
