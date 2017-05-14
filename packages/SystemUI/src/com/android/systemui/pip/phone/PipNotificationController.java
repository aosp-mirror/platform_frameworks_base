/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpChangedListener;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.util.NotificationChannels;

/**
 * Manages the BTW notification that shows whenever an activity enters or leaves picture-in-picture.
 */
public class PipNotificationController {
    private static final String TAG = PipNotificationController.class.getSimpleName();

    private static final String NOTIFICATION_TAG = PipNotificationController.class.getName();
    private static final int NOTIFICATION_ID = 0;

    private Context mContext;
    private IActivityManager mActivityManager;
    private AppOpsManager mAppOpsManager;
    private NotificationManager mNotificationManager;

    private PipMotionHelper mMotionHelper;

    // Used when building a deferred notification
    private String mDeferredNotificationPackageName;

    private AppOpsManager.OnOpChangedListener mAppOpsChangedListener = new OnOpChangedListener() {
        @Override
        public void onOpChanged(String op, String packageName) {
            try {
                // Dismiss the PiP once the user disables the app ops setting for that package
                final ApplicationInfo appInfo = mContext.getPackageManager().getApplicationInfo(
                        packageName, 0);
                if (mAppOpsManager.checkOpNoThrow(OP_PICTURE_IN_PICTURE, appInfo.uid, packageName)
                        != MODE_ALLOWED) {
                    mMotionHelper.dismissPip();
                }
            } catch (NameNotFoundException e) {
                // Unregister the listener if the package can't be found
                unregisterAppOpsListener();
            }
        }
    };

    public PipNotificationController(Context context, IActivityManager activityManager,
            PipMotionHelper motionHelper) {
        mContext = context;
        mActivityManager = activityManager;
        mAppOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mNotificationManager = NotificationManager.from(context);
        mMotionHelper = motionHelper;
    }

    public void onActivityPinned(String packageName, boolean deferUntilAnimationEnds) {
        // Clear any existing notification
        mNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);

        if (deferUntilAnimationEnds) {
            mDeferredNotificationPackageName = packageName;
        } else {
            showNotificationForApp(mDeferredNotificationPackageName);
        }

        // Register for changes to the app ops setting for this package while it is in PiP
        registerAppOpsListener(packageName);
    }

    public void onPinnedStackAnimationEnded() {
        if (mDeferredNotificationPackageName != null) {
            showNotificationForApp(mDeferredNotificationPackageName);
            mDeferredNotificationPackageName = null;
        }
    }

    public void onActivityUnpinned(ComponentName topPipActivity) {
        // Unregister for changes to the previously PiP'ed package
        unregisterAppOpsListener();

        // Reset the deferred notification package
        mDeferredNotificationPackageName = null;

        if (topPipActivity != null) {
            // onActivityUnpinned() is only called after the transition is complete, so we don't
            // need to defer until the animation ends to update the notification
            onActivityPinned(topPipActivity.getPackageName(), false /* deferUntilAnimationEnds */);
        } else {
            mNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        }
    }

    /**
     * Builds and shows the notification for the given app.
     */
    private void showNotificationForApp(String packageName) {
        // Build a new notification
        final Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.GENERAL)
                        .setLocalOnly(true)
                        .setOngoing(true)
                        .setSmallIcon(R.drawable.pip_notification_icon)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color));
        if (updateNotificationForApp(builder, packageName)) {
            SystemUI.overrideNotificationAppName(mContext, builder);

            // Show the new notification
            mNotificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
        }
    }

    /**
     * Updates the notification builder with app-specific information, returning whether it was
     * successful.
     */
    private boolean updateNotificationForApp(Notification.Builder builder, String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not update notification for application", e);
            return false;
        }

        if (appInfo != null) {
            final String appName = pm.getApplicationLabel(appInfo).toString();
            final String message = mContext.getString(R.string.pip_notification_message, appName);
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", packageName, null));
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
            final Icon appIcon = appInfo.icon != 0
                    ? Icon.createWithResource(packageName, appInfo.icon)
                    : Icon.createWithResource(Resources.getSystem(),
                            com.android.internal.R.drawable.sym_def_app_icon);

            builder.setContentTitle(mContext.getString(R.string.pip_notification_title, appName))
                    .setContentText(message)
                    .setContentIntent(PendingIntent.getActivity(mContext, packageName.hashCode(),
                            settingsIntent, FLAG_CANCEL_CURRENT))
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setLargeIcon(appIcon);
            return true;
        }
        return false;
    }

    private void registerAppOpsListener(String packageName) {
        mAppOpsManager.startWatchingMode(OP_PICTURE_IN_PICTURE, packageName,
                mAppOpsChangedListener);
    }

    private void unregisterAppOpsListener() {
        mAppOpsManager.stopWatchingMode(mAppOpsChangedListener);
    }
}
