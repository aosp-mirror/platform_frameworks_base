/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.packageinstaller;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

/**
 * A util class that handle and post new app installed notifications.
 */
class PackageInstalledNotificationUtils {
    private static final String TAG = PackageInstalledNotificationUtils.class.getSimpleName();

    private static final String NEW_APP_INSTALLED_CHANNEL_ID_PREFIX = "INSTALLER:";
    private static final String META_DATA_INSTALLER_NOTIFICATION_SMALL_ICON_KEY =
            "com.android.packageinstaller.notification.smallIcon";
    private static final String META_DATA_INSTALLER_NOTIFICATION_COLOR_KEY =
            "com.android.packageinstaller.notification.color";

    private static final float DEFAULT_MAX_LABEL_SIZE_PX = 500f;

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    private final String mInstallerPackage;
    private final String mInstallerAppLabel;
    private final Icon mInstallerAppSmallIcon;
    private final Integer mInstallerAppColor;

    private final String mInstalledPackage;
    private final String mInstalledAppLabel;
    private final Icon mInstalledAppLargeIcon;

    private final String mChannelId;

    PackageInstalledNotificationUtils(@NonNull Context context, @NonNull String installerPackage,
            @NonNull String installedPackage) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        ApplicationInfo installerAppInfo;
        ApplicationInfo installedAppInfo;

        try {
            installerAppInfo = context.getPackageManager().getApplicationInfo(installerPackage,
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            // Should not happen
            throw new IllegalStateException("Unable to get application info: " + installerPackage);
        }
        try {
            installedAppInfo = context.getPackageManager().getApplicationInfo(installedPackage,
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            // Should not happen
            throw new IllegalStateException("Unable to get application info: " + installedPackage);
        }
        mInstallerPackage = installerPackage;
        mInstallerAppLabel = getAppLabel(context, installerAppInfo, installerPackage);
        mInstallerAppSmallIcon = getAppNotificationIcon(context, installerAppInfo);
        mInstallerAppColor = getAppNotificationColor(context, installerAppInfo);

        mInstalledPackage = installedPackage;
        mInstalledAppLabel = getAppLabel(context, installedAppInfo, installerPackage);
        mInstalledAppLargeIcon = getAppLargeIcon(installedAppInfo);

        mChannelId = NEW_APP_INSTALLED_CHANNEL_ID_PREFIX + installerPackage;
    }

    /**
     * Get app label from app's manifest.
     *
     * @param context     A context of the current app
     * @param appInfo     Application info of targeted app
     * @param packageName Package name of targeted app
     * @return The label of targeted application, or package name if label is not found
     */
    private static String getAppLabel(@NonNull Context context, @NonNull ApplicationInfo appInfo,
            @NonNull String packageName) {
        CharSequence label = appInfo.loadSafeLabel(context.getPackageManager(),
                DEFAULT_MAX_LABEL_SIZE_PX,
                PackageItemInfo.SAFE_LABEL_FLAG_TRIM
                        | PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE).toString();
        if (label != null) {
            return label.toString();
        }
        return packageName;
    }

    /**
     * The app icon from app's manifest.
     *
     * @param appInfo Application info of targeted app
     * @return App icon of targeted app, or Android default app icon if icon is not found
     */
    private static Icon getAppLargeIcon(@NonNull ApplicationInfo appInfo) {
        if (appInfo.icon != 0) {
            return Icon.createWithResource(appInfo.packageName, appInfo.icon);
        } else {
            return Icon.createWithResource("android", android.R.drawable.sym_def_app_icon);
        }
    }

    /**
     * Get notification icon from installer's manifest meta-data.
     *
     * @param context A context of the current app
     * @param appInfo Installer application info
     * @return Notification icon that listed in installer's manifest meta-data.
     * If icon is not found in meta-data, then it returns Android default download icon.
     */
    private static Icon getAppNotificationIcon(@NonNull Context context,
            @NonNull ApplicationInfo appInfo) {
        if (appInfo.metaData == null) {
            return Icon.createWithResource(context, R.drawable.ic_file_download);
        }

        int iconResId = appInfo.metaData.getInt(
                META_DATA_INSTALLER_NOTIFICATION_SMALL_ICON_KEY, 0);
        if (iconResId != 0) {
            return Icon.createWithResource(appInfo.packageName, iconResId);
        }
        return Icon.createWithResource(context, R.drawable.ic_file_download);
    }

    /**
     * Get notification color from installer's manifest meta-data.
     *
     * @param context A context of the current app
     * @param appInfo Installer application info
     * @return Notification color that listed in installer's manifest meta-data, or null if
     * meta-data is not found.
     */
    private static Integer getAppNotificationColor(@NonNull Context context,
            @NonNull ApplicationInfo appInfo) {
        if (appInfo.metaData == null) {
            return null;
        }

        int colorResId = appInfo.metaData.getInt(
                META_DATA_INSTALLER_NOTIFICATION_COLOR_KEY, 0);
        if (colorResId != 0) {
            try {
                PackageManager pm = context.getPackageManager();
                Resources resources = pm.getResourcesForApplication(appInfo.packageName);
                return resources.getColor(colorResId, context.getTheme());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Error while loading notification color: " + colorResId + " for "
                        + appInfo.packageName);
            }
        }
        return null;
    }

    private static Intent getAppDetailIntent(@NonNull String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", packageName, null));
        return intent;
    }

    private static Intent resolveIntent(@NonNull Context context, @NonNull Intent i) {
        ResolveInfo result = context.getPackageManager().resolveActivity(i, 0);
        if (result == null) {
            return null;
        }
        return new Intent(i.getAction()).setClassName(result.activityInfo.packageName,
                result.activityInfo.name);
    }

    private static Intent getAppStoreLink(@NonNull Context context,
            @NonNull String installerPackageName, @NonNull String packageName) {
        Intent intent = new Intent(Intent.ACTION_SHOW_APP_INFO)
                .setPackage(installerPackageName);

        Intent result = resolveIntent(context, intent);
        if (result != null) {
            result.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            return result;
        }
        return null;
    }

    /**
     * Create notification channel for showing apps installed notifications.
     */
    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(mChannelId, mInstallerAppLabel,
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(
                mContext.getString(R.string.app_installed_notification_channel_description));
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setBlockable(true);

        mNotificationManager.createNotificationChannel(channel);
    }

    /**
     * Returns a pending intent when user clicks on apps installed notification.
     * It should launch the app if possible, otherwise it will return app store's app page.
     * If app store's app page is not available, it will return Android app details page.
     */
    private PendingIntent getInstalledAppLaunchIntent() {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mInstalledPackage);

        // If installed app does not have a launch intent, bring user to app store page
        if (intent == null) {
            intent = getAppStoreLink(mContext, mInstallerPackage, mInstalledPackage);
        }

        // If app store cannot handle this, bring user to app settings page
        if (intent == null) {
            intent = getAppDetailIntent(mInstalledPackage);
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(mContext,
                0 /* request code */, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Returns a pending intent that starts installer's launch intent.
     * If it doesn't have a launch intent, it will return installer's Android app details page.
     */
    private PendingIntent getInstallerEntranceIntent() {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mInstallerPackage);

        // If installer does not have a launch intent, bring user to app settings page
        if (intent == null) {
            intent = getAppDetailIntent(mInstallerPackage);
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(mContext,
                0 /* request code */, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Returns a notification builder for grouped notifications.
     */
    private Notification.Builder getGroupNotificationBuilder() {
        PendingIntent contentIntent = getInstallerEntranceIntent();

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, mInstallerAppLabel);

        Notification.Builder builder =
                new Notification.Builder(mContext, mChannelId)
                        .setSmallIcon(mInstallerAppSmallIcon)
                        .setGroup(mChannelId)
                        .setExtras(extras)
                        .setLocalOnly(true)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setContentIntent(contentIntent)
                        .setGroupSummary(true);

        if (mInstallerAppColor != null) {
            builder.setColor(mInstallerAppColor);
        }
        return builder;
    }

    /**
     * Returns notification build for individual installed applications.
     */
    private Notification.Builder getAppInstalledNotificationBuilder() {
        PendingIntent contentIntent = getInstalledAppLaunchIntent();

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, mInstallerAppLabel);

        String tickerText = String.format(
                mContext.getString(R.string.notification_installation_success_status),
                mInstalledAppLabel);

        Notification.Builder builder =
                new Notification.Builder(mContext, mChannelId)
                        .setAutoCancel(true)
                        .setSmallIcon(mInstallerAppSmallIcon)
                        .setContentTitle(mInstalledAppLabel)
                        .setContentText(mContext.getString(
                                R.string.notification_installation_success_message))
                        .setContentIntent(contentIntent)
                        .setTicker(tickerText)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setShowWhen(true)
                        .setWhen(System.currentTimeMillis())
                        .setLocalOnly(true)
                        .setGroup(mChannelId)
                        .addExtras(extras)
                        .setStyle(new Notification.BigTextStyle());

        if (mInstalledAppLargeIcon != null) {
            builder.setLargeIcon(mInstalledAppLargeIcon);
        }
        if (mInstallerAppColor != null) {
            builder.setColor(mInstallerAppColor);
        }
        return builder;
    }

    /**
     * Post new app installed notification.
     */
    void postAppInstalledNotification() {
        createChannel();

        // Post app installed notification
        Notification.Builder appNotificationBuilder = getAppInstalledNotificationBuilder();
        mNotificationManager.notify(mInstalledPackage, mInstalledPackage.hashCode(),
                appNotificationBuilder.build());

        // Post installer group notification
        Notification.Builder groupNotificationBuilder = getGroupNotificationBuilder();
        mNotificationManager.notify(mInstallerPackage, mInstallerPackage.hashCode(),
                groupNotificationBuilder.build());
    }
}
