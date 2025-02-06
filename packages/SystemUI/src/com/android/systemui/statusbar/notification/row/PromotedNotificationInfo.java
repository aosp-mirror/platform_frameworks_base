/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * The guts of a notification revealed when performing a long press, specifically
 * for notifications that are shown as promoted. Contains extra controls to allow user to revoke
 * app permissions for sending promoted notifications.
 */
public class PromotedNotificationInfo extends NotificationInfo {
    private static final String TAG = "PromotedNotifInfoGuts";
    private INotificationManager mNotificationManager;

    public PromotedNotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void bindNotification(
            PackageManager pm,
            INotificationManager iNotificationManager,
            OnUserInteractionCallback onUserInteractionCallback,
            ChannelEditorDialogController channelEditorDialogController,
            String pkg,
            NotificationChannel notificationChannel,
            NotificationEntry entry,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            OnFeedbackClickListener feedbackClickListener,
            UiEventLogger uiEventLogger,
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            boolean wasShownHighPriority,
            AssistantFeedbackController assistantFeedbackController,
            MetricsLogger metricsLogger, OnClickListener onCloseClick) throws RemoteException {
        super.bindNotification(pm, iNotificationManager, onUserInteractionCallback,
                channelEditorDialogController, pkg, notificationChannel, entry, onSettingsClick,
                onAppSettingsClick, feedbackClickListener, uiEventLogger, isDeviceProvisioned,
                isNonblockable, wasShownHighPriority, assistantFeedbackController, metricsLogger,
                onCloseClick);

        mNotificationManager = iNotificationManager;

        bindDismiss(entry.getSbn(), onCloseClick);
        bindDemote(entry.getSbn(), pkg);
    }


    protected void bindDismiss(StatusBarNotification sbn,
            View.OnClickListener onCloseClick) {
        View dismissButton = findViewById(R.id.promoted_dismiss);

        dismissButton.setOnClickListener(onCloseClick);
        dismissButton.setVisibility(!sbn.isNonDismissable()
                && dismissButton.hasOnClickListeners() ? VISIBLE : GONE);

    }

    protected void bindDemote(StatusBarNotification sbn, String packageName) {
        View demoteButton = findViewById(R.id.promoted_demote);
        demoteButton.setOnClickListener(getDemoteClickListener(sbn, packageName));
        demoteButton.setVisibility(demoteButton.hasOnClickListeners() ? VISIBLE : GONE);
    }

    private OnClickListener getDemoteClickListener(StatusBarNotification sbn, String packageName) {
        return ((View unusedView) -> {
            try {
                // TODO(b/391661009): Signal AutomaticPromotionCoordinator here
                mNotificationManager.setCanBePromoted(packageName, sbn.getUid(), false, true);
            } catch (RemoteException e) {
                Log.e(TAG, "Couldn't revoke live update permission", e);
            }
        });
    }
}

