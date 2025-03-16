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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class BundleNotificationInfo extends NotificationInfo {
    private static final String TAG = "BundleNotifInfoGuts";

    public BundleNotificationInfo(Context context, AttributeSet attrs) {
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
            UiEventLogger uiEventLogger,
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            boolean wasShownHighPriority,
            AssistantFeedbackController assistantFeedbackController,
            MetricsLogger metricsLogger) throws RemoteException {
        super.bindNotification(pm, iNotificationManager, onUserInteractionCallback,
                channelEditorDialogController, pkg, notificationChannel, entry, onSettingsClick,
                onAppSettingsClick, uiEventLogger, isDeviceProvisioned, isNonblockable,
                wasShownHighPriority, assistantFeedbackController, metricsLogger);

        // Additionally, bind the feedback button.
        ComponentName assistant = iNotificationManager.getAllowedNotificationAssistant();
        bindFeedback(entry.getSbn(), pm, assistant, onAppSettingsClick);
    }

    protected void bindFeedback(StatusBarNotification sbn, PackageManager pm,
            ComponentName assistant,
            NotificationInfo.OnAppSettingsClickListener appSettingsClickListener) {
        View feedbackButton = findViewById(R.id.notification_guts_bundle_feedback);
        // If the assistant component is null, don't show the feedback button and finish.
        if (assistant == null) {
            feedbackButton.setVisibility(GONE);
            return;
        }
        // Otherwise we extract the assistant package name.
        String assistantPkg = assistant.getPackageName();

        feedbackButton.setOnClickListener(getBundleFeedbackClickListener(sbn, pm, assistantPkg,
                appSettingsClickListener));
        feedbackButton.setVisibility(feedbackButton.hasOnClickListeners() ? VISIBLE : GONE);
    }

    private OnClickListener getBundleFeedbackClickListener(StatusBarNotification sbn,
            PackageManager pm, String assistantPkg,
            NotificationInfo.OnAppSettingsClickListener appSettingsClickListener) {
        Intent feedbackIntent = getBundleFeedbackIntent(pm, assistantPkg, sbn.getKey());
        if (feedbackIntent != null) {
            return ((View view) -> {
                appSettingsClickListener.onClick(view, feedbackIntent);
            });
        }
        return null;
    }

    private Intent getBundleFeedbackIntent(PackageManager pm, String packageName, String key) {
        Intent intent = new Intent(
                NotificationAssistantService.ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS)
                .setPackage(packageName);
        final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (resolveInfos == null || resolveInfos.size() == 0 || resolveInfos.get(0) == null) {
            return null;
        }
        final ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
        intent.setClassName(activityInfo.packageName, activityInfo.name);
        intent.putExtra(NotificationAssistantService.EXTRA_NOTIFICATION_KEY, key);
        return intent;
    }
}

