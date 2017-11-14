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
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.Pair;

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
    private IconDrawableFactory mIconDrawableFactory;

    private PipMotionHelper mMotionHelper;

    // Used when building a deferred notification
    private String mDeferredNotificationPackageName;
    private int mDeferredNotificationUserId;

    private AppOpsManager.OnOpChangedListener mAppOpsChangedListener = new OnOpChangedListener() {
        @Override
        public void onOpChanged(String op, String packageName) {
            try {
                // Dismiss the PiP once the user disables the app ops setting for that package
                final Pair<ComponentName, Integer> topPipActivityInfo =
                        PipUtils.getTopPinnedActivity(mContext, mActivityManager);
                if (topPipActivityInfo.first != null) {
                    final ApplicationInfo appInfo = mContext.getPackageManager()
                            .getApplicationInfoAsUser(packageName, 0, topPipActivityInfo.second);
                    if (appInfo.packageName.equals(topPipActivityInfo.first.getPackageName()) &&
                                mAppOpsManager.checkOpNoThrow(OP_PICTURE_IN_PICTURE, appInfo.uid,
                                        packageName) != MODE_ALLOWED) {
                        mMotionHelper.dismissPip();
                    }
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
        mIconDrawableFactory = IconDrawableFactory.newInstance(context);
    }

    public void onActivityPinned(String packageName, int userId, boolean deferUntilAnimationEnds) {
        // Clear any existing notification
        mNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);

        if (deferUntilAnimationEnds) {
            mDeferredNotificationPackageName = packageName;
            mDeferredNotificationUserId = userId;
        } else {
            showNotificationForApp(packageName, userId);
        }

        // Register for changes to the app ops setting for this package while it is in PiP
        registerAppOpsListener(packageName);
    }

    public void onPinnedStackAnimationEnded() {
        if (mDeferredNotificationPackageName != null) {
            showNotificationForApp(mDeferredNotificationPackageName, mDeferredNotificationUserId);
            mDeferredNotificationPackageName = null;
            mDeferredNotificationUserId = 0;
        }
    }

    public void onActivityUnpinned(ComponentName topPipActivity, int userId) {
        // Unregister for changes to the previously PiP'ed package
        unregisterAppOpsListener();

        // Reset the deferred notification package
        mDeferredNotificationPackageName = null;
        mDeferredNotificationUserId = 0;

        if (topPipActivity != null) {
            // onActivityUnpinned() is only called after the transition is complete, so we don't
            // need to defer until the animation ends to update the notification
            onActivityPinned(topPipActivity.getPackageName(), userId,
                    false /* deferUntilAnimationEnds */);
        } else {
            mNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        }
    }

    /**
     * Builds and shows the notification for the given app.
     */
    private void showNotificationForApp(String packageName, int userId) {
        // Build a new notification
        try {
            final UserHandle user = UserHandle.of(userId);
            final Context userContext = mContext.createPackageContextAsUser(
                    mContext.getPackageName(), 0, user);
            final Notification.Builder builder =
                    new Notification.Builder(userContext, NotificationChannels.GENERAL)
                            .setLocalOnly(true)
                            .setOngoing(true)
                            .setSmallIcon(R.drawable.pip_notification_icon)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color));
            if (updateNotificationForApp(builder, packageName, user)) {
                SystemUI.overrideNotificationAppName(mContext, builder);

                // Show the new notification
                mNotificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not show notification for application", e);
        }
    }

    /**
     * Updates the notification builder with app-specific information, returning whether it was
     * successful.
     */
    private boolean updateNotificationForApp(Notification.Builder builder, String packageName,
            UserHandle user) throws NameNotFoundException {
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfoAsUser(packageName, 0, user.getIdentifier());
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not update notification for application", e);
            return false;
        }

        if (appInfo != null) {
            final String appName = pm.getUserBadgedLabel(pm.getApplicationLabel(appInfo), user)
                    .toString();
            final String message = mContext.getString(R.string.pip_notification_message, appName);
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", packageName, null));
            settingsIntent.putExtra(Intent.EXTRA_USER_HANDLE, user);
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);

            final Drawable iconDrawable = mIconDrawableFactory.getBadgedIcon(appInfo);
            builder.setContentTitle(mContext.getString(R.string.pip_notification_title, appName))
                    .setContentText(message)
                    .setContentIntent(PendingIntent.getActivityAsUser(mContext, packageName.hashCode(),
                            settingsIntent, FLAG_CANCEL_CURRENT, null, user))
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setLargeIcon(createBitmap(iconDrawable).createAshmemBitmap());
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

    /**
     * Bakes a drawable into a bitmap.
     */
    private Bitmap createBitmap(Drawable d) {
        Bitmap bitmap = Bitmap.createBitmap(d.getIntrinsicWidth(), d.getIntrinsicHeight(),
                Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        d.draw(c);
        c.setBitmap(null);
        return bitmap;
    }
}
