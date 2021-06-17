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

package com.android.systemui.statusbar.tv.notifications;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;

/**
 * Offers control methods for the notification panel handler on TV devices.
 */
@SysUISingleton
public class TvNotificationPanel extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "TvNotificationPanel";
    private final CommandQueue mCommandQueue;
    private final String mNotificationHandlerPackage;

    @Inject
    public TvNotificationPanel(Context context, CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
        mNotificationHandlerPackage = mContext.getResources().getString(
                com.android.internal.R.string.config_notificationHandlerPackage);
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    public void togglePanel() {
        if (!mNotificationHandlerPackage.isEmpty()) {
            startNotificationHandlerActivity(
                    new Intent(NotificationManager.ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL));
        } else {
            openInternalNotificationPanel(
                    NotificationManager.ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL);
        }
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (!mNotificationHandlerPackage.isEmpty()) {
            startNotificationHandlerActivity(
                    new Intent(NotificationManager.ACTION_OPEN_NOTIFICATION_HANDLER_PANEL));
        } else {
            openInternalNotificationPanel(
                    NotificationManager.ACTION_OPEN_NOTIFICATION_HANDLER_PANEL);
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        if (!mNotificationHandlerPackage.isEmpty()
                && (flags & CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL) == 0) {
            Intent closeNotificationIntent = new Intent(
                    NotificationManager.ACTION_CLOSE_NOTIFICATION_HANDLER_PANEL);
            closeNotificationIntent.setPackage(mNotificationHandlerPackage);
            mContext.sendBroadcastAsUser(closeNotificationIntent, UserHandle.CURRENT);
        } else {
            openInternalNotificationPanel(
                    NotificationManager.ACTION_CLOSE_NOTIFICATION_HANDLER_PANEL);
        }
    }

    private void openInternalNotificationPanel(String action) {
        Intent intent = new Intent(mContext, TvNotificationPanelActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setAction(action);
        mContext.startActivityAsUser(intent, UserHandle.SYSTEM);
    }

    /**
     * Starts the activity intent if all of the following are true
     * <ul>
     * <li> the notification handler package is a system component </li>
     * <li> the provided intent is handled by the notification handler package </li>
     * <li> the notification handler requests the
     * {@link android.Manifest.permission#STATUS_BAR_SERVICE} permission for the given intent</li>
     * </ul>
     *
     * @param intent The intent for starting the desired activity
     */
    private void startNotificationHandlerActivity(Intent intent) {
        intent.setPackage(mNotificationHandlerPackage);
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (ri != null && ri.activityInfo != null) {
            if (ri.activityInfo.permission != null && ri.activityInfo.permission.equals(
                    Manifest.permission.STATUS_BAR_SERVICE)) {
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            } else {
                Log.e(TAG,
                        "Not launching notification handler activity: Notification handler does "
                                + "not require the STATUS_BAR_SERVICE permission for intent "
                                + intent.getAction());
            }
        } else {
            Log.e(TAG,
                    "Not launching notification handler activity: Could not resolve activityInfo "
                            + "for intent "
                            + intent.getAction());
        }
    }
}
