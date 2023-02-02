/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.devicestate;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Manages the user-visible device state notifications.
 */
class DeviceStateNotificationController extends BroadcastReceiver {
    private static final String TAG = "DeviceStateNotificationController";

    @VisibleForTesting static final String INTENT_ACTION_CANCEL_STATE =
            "com.android.server.devicestate.INTENT_ACTION_CANCEL_STATE";
    @VisibleForTesting static final int NOTIFICATION_ID = 1;
    @VisibleForTesting static final String CHANNEL_ID = "DeviceStateManager";
    @VisibleForTesting static final String NOTIFICATION_TAG = "DeviceStateManager";

    private final Context mContext;
    private final Handler mHandler;
    private final NotificationManager mNotificationManager;
    private final PackageManager mPackageManager;

    // Stores the notification title and content indexed with the device state identifier.
    private final SparseArray<NotificationInfo> mNotificationInfos;

    // The callback when a device state is requested to be canceled.
    private final Runnable mCancelStateRunnable;

    DeviceStateNotificationController(@NonNull Context context, @NonNull Handler handler,
            @NonNull Runnable cancelStateRunnable) {
        this(context, handler, cancelStateRunnable, getNotificationInfos(context),
                context.getPackageManager(), context.getSystemService(NotificationManager.class));
    }

    @VisibleForTesting
    DeviceStateNotificationController(
            @NonNull Context context, @NonNull Handler handler,
            @NonNull Runnable cancelStateRunnable,
            @NonNull SparseArray<NotificationInfo> notificationInfos,
            @NonNull PackageManager packageManager,
            @NonNull NotificationManager notificationManager) {
        mContext = context;
        mHandler = handler;
        mCancelStateRunnable = cancelStateRunnable;
        mNotificationInfos = notificationInfos;
        mPackageManager = packageManager;
        mNotificationManager = notificationManager;
        mContext.registerReceiver(
                this,
                new IntentFilter(INTENT_ACTION_CANCEL_STATE),
                android.Manifest.permission.CONTROL_DEVICE_STATE,
                mHandler,
                Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Displays the ongoing notification indicating that the device state is active. Does nothing if
     * the state does not have an active notification.
     *
     * @param state the active device state identifier.
     * @param requestingAppUid the uid of the requesting app used to retrieve the app name.
     */
    void showStateActiveNotificationIfNeeded(int state, int requestingAppUid) {
        NotificationInfo info = mNotificationInfos.get(state);
        if (info == null || !info.hasActiveNotification()) {
            return;
        }
        String requesterApplicationLabel = getApplicationLabel(requestingAppUid);
        if (requesterApplicationLabel != null) {
            showNotification(
                    info.name, info.activeNotificationTitle,
                    String.format(info.activeNotificationContent, requesterApplicationLabel),
                    true /* ongoing */, R.drawable.ic_dual_screen
            );
        } else {
            Slog.e(TAG, "Cannot determine the requesting app name when showing state active "
                    + "notification. uid=" + requestingAppUid + ", state=" + state);
        }
    }

    /**
     * Displays the notification indicating that the device state is canceled due to thermal
     * critical condition. Does nothing if the state does not have a thermal critical notification.
     *
     * @param state the identifier of the device state being canceled.
     */
    void showThermalCriticalNotificationIfNeeded(int state) {
        NotificationInfo info = mNotificationInfos.get(state);
        if (info == null || !info.hasThermalCriticalNotification()) {
            return;
        }
        showNotification(
                info.name, info.thermalCriticalNotificationTitle,
                info.thermalCriticalNotificationContent, false /* ongoing */,
                R.drawable.ic_thermostat
        );
    }

    /**
     * Cancels the notification of the corresponding device state.
     *
     * @param state the device state identifier.
     */
    void cancelNotification(int state) {
        if (!mNotificationInfos.contains(state)) {
            return;
        }
        mNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
    }

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent != null) {
            if (INTENT_ACTION_CANCEL_STATE.equals(intent.getAction())) {
                mCancelStateRunnable.run();
            }
        }
    }

    /**
     * Displays a notification with the specified name, title, and content.
     *
     * @param name the name of the notification.
     * @param title the title of the notification.
     * @param content the content of the notification.
     * @param ongoing if true, display an ongoing (sticky) notification with a turn off button.
     */
    private void showNotification(
            @NonNull String name, @NonNull String title, @NonNull String content, boolean ongoing,
            @DrawableRes int iconRes) {
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH);
        final Notification.Builder builder = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(name)
                .setLocalOnly(true)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_SYSTEM);

        if (ongoing) {
            final Intent intent = new Intent(INTENT_ACTION_CANCEL_STATE)
                    .setPackage(mContext.getPackageName());
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext, 0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE);
            final Notification.Action action = new Notification.Action.Builder(
                    null /* icon */,
                    mContext.getString(R.string.device_state_notification_turn_off_button),
                    pendingIntent)
                    .build();
            builder.addAction(action);
        }

        mNotificationManager.createNotificationChannel(channel);
        mNotificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
    }

    /**
     * Loads the resources for the notifications. The device state identifiers and strings are
     * stored in arrays. All the string arrays must have the same length and same order as the
     * identifier array.
     */
    private static SparseArray<NotificationInfo> getNotificationInfos(Context context) {
        final SparseArray<NotificationInfo> notificationInfos = new SparseArray<>();

        final int[] stateIdentifiers =
                context.getResources().getIntArray(
                        R.array.device_state_notification_state_identifiers);
        final String[] names =
                context.getResources().getStringArray(R.array.device_state_notification_names);
        final String[] activeNotificationTitles =
                context.getResources().getStringArray(
                        R.array.device_state_notification_active_titles);
        final String[] activeNotificationContents =
                context.getResources().getStringArray(
                        R.array.device_state_notification_active_contents);
        final String[] thermalCriticalNotificationTitles =
                context.getResources().getStringArray(
                        R.array.device_state_notification_thermal_titles);
        final String[] thermalCriticalNotificationContents =
                context.getResources().getStringArray(
                        R.array.device_state_notification_thermal_contents);

        if (stateIdentifiers.length != names.length
                || stateIdentifiers.length != activeNotificationTitles.length
                || stateIdentifiers.length != activeNotificationContents.length
                || stateIdentifiers.length != thermalCriticalNotificationTitles.length
                || stateIdentifiers.length != thermalCriticalNotificationContents.length
        ) {
            throw new IllegalStateException(
                    "The length of state identifiers and notification texts must match!");
        }

        for (int i = 0; i < stateIdentifiers.length; i++) {
            int identifier = stateIdentifiers[i];
            if (identifier == DeviceStateManager.INVALID_DEVICE_STATE) {
                continue;
            }

            notificationInfos.put(
                    identifier,
                    new NotificationInfo(
                            names[i], activeNotificationTitles[i], activeNotificationContents[i],
                            thermalCriticalNotificationTitles[i],
                            thermalCriticalNotificationContents[i])
            );
        }

        return notificationInfos;
    }

    /**
     * A helper function to get app name (label) using the app uid.
     *
     * @param uid the uid of the app.
     * @return app name (label) if found, or null otherwise.
     */
    @Nullable
    private String getApplicationLabel(int uid) {
        String packageName = mPackageManager.getNameForUid(uid);
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
            return appInfo.loadLabel(mPackageManager).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * A data class storing string resources of the notification of a device state.
     */
    @VisibleForTesting
    static class NotificationInfo {
        public final String name;
        public final String activeNotificationTitle;
        public final String activeNotificationContent;
        public final String thermalCriticalNotificationTitle;
        public final String thermalCriticalNotificationContent;

        NotificationInfo(String name, String activeNotificationTitle,
                String activeNotificationContent, String thermalCriticalNotificationTitle,
                String thermalCriticalNotificationContent) {

            this.name = name;
            this.activeNotificationTitle = activeNotificationTitle;
            this.activeNotificationContent = activeNotificationContent;
            this.thermalCriticalNotificationTitle = thermalCriticalNotificationTitle;
            this.thermalCriticalNotificationContent = thermalCriticalNotificationContent;
        }

        boolean hasActiveNotification() {
            return activeNotificationTitle != null && activeNotificationTitle.length() > 0;
        }

        boolean hasThermalCriticalNotification() {
            return thermalCriticalNotificationTitle != null
                    && thermalCriticalNotificationTitle.length() > 0;
        }
    }
}
