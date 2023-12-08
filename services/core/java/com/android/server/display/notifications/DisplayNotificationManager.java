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

package com.android.server.display.notifications;

import static android.app.Notification.COLOR_DEFAULT;

import static com.android.internal.notification.SystemNotificationChannels.ALERTS;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.DisplayManagerFlags;

/**
 * Manages notifications for {@link com.android.server.display.DisplayManagerService}.
 */
public class DisplayNotificationManager implements ConnectedDisplayUsbErrorsDetector.Listener {
    /** Dependency injection interface for {@link DisplayNotificationManager} */
    public interface Injector {
        /** Get {@link NotificationManager} service or null if not available. */
        @Nullable
        NotificationManager getNotificationManager();

        /** Get {@link ConnectedDisplayUsbErrorsDetector} or null if not available. */
        @Nullable
        ConnectedDisplayUsbErrorsDetector getUsbErrorsDetector();
    }

    private static final String TAG = "DisplayNotificationManager";
    private static final String NOTIFICATION_GROUP_NAME = TAG;
    private static final String DISPLAY_NOTIFICATION_TAG = TAG;
    private static final int DISPLAY_NOTIFICATION_ID = 1;
    private static final long NOTIFICATION_TIMEOUT_MILLISEC = 30000L;

    private final Injector mInjector;
    private final Context mContext;
    private final boolean mConnectedDisplayErrorHandlingEnabled;
    private NotificationManager mNotificationManager;
    private ConnectedDisplayUsbErrorsDetector mConnectedDisplayUsbErrorsDetector;

    public DisplayNotificationManager(final DisplayManagerFlags flags, final Context context) {
        this(flags, context, new Injector() {
            @Nullable
            @Override
            public NotificationManager getNotificationManager() {
                return context.getSystemService(NotificationManager.class);
            }

            @Nullable
            @Override
            public ConnectedDisplayUsbErrorsDetector getUsbErrorsDetector() {
                return new ConnectedDisplayUsbErrorsDetector(flags, context);
            }
        });
    }

    @VisibleForTesting
    DisplayNotificationManager(final DisplayManagerFlags flags, final Context context,
            final Injector injector) {
        mConnectedDisplayErrorHandlingEnabled = flags.isConnectedDisplayErrorHandlingEnabled();
        mContext = context;
        mInjector = injector;
    }

    /**
     * Initialize services, which may be not yet published during boot.
     * see {@link android.os.ServiceManager.ServiceNotFoundException}.
     */
    public void onBootCompleted() {
        mNotificationManager = mInjector.getNotificationManager();
        if (mNotificationManager == null) {
            Slog.e(TAG, "onBootCompleted: NotificationManager is null");
            return;
        }

        mConnectedDisplayUsbErrorsDetector = mInjector.getUsbErrorsDetector();
        if (mConnectedDisplayUsbErrorsDetector != null) {
            mConnectedDisplayUsbErrorsDetector.registerListener(this);
        }
    }

    /**
     * Display error notification upon DisplayPort link training failure.
     */
    @Override
    public void onDisplayPortLinkTrainingFailure() {
        if (!mConnectedDisplayErrorHandlingEnabled) {
            Slog.d(TAG, "onDisplayPortLinkTrainingFailure:"
                                + " mConnectedDisplayErrorHandlingEnabled is false");
            return;
        }

        sendErrorNotification(createErrorNotification(
                R.string.connected_display_unavailable_notification_title,
                R.string.connected_display_unavailable_notification_content,
                R.drawable.usb_cable_unknown_issue));
    }

    /**
     * Display error notification upon cable not capable of DisplayPort connected to a device
     * capable of DisplayPort.
     */
    @Override
    public void onCableNotCapableDisplayPort() {
        if (!mConnectedDisplayErrorHandlingEnabled) {
            Slog.d(TAG, "onCableNotCapableDisplayPort:"
                                + " mConnectedDisplayErrorHandlingEnabled is false");
            return;
        }

        sendErrorNotification(createErrorNotification(
                R.string.connected_display_unavailable_notification_title,
                R.string.connected_display_unavailable_notification_content,
                R.drawable.usb_cable_unknown_issue));
    }

    /**
     * Send notification about hotplug connection error.
     */
    public void onHotplugConnectionError() {
        if (!mConnectedDisplayErrorHandlingEnabled) {
            Slog.d(TAG, "onHotplugConnectionError:"
                                + " mConnectedDisplayErrorHandlingEnabled is false");
            return;
        }

        sendErrorNotification(createErrorNotification(
                R.string.connected_display_unavailable_notification_title,
                R.string.connected_display_unavailable_notification_content,
                R.drawable.usb_cable_unknown_issue));
    }

    /**
     * Send notification about high temperature preventing usage of the external display.
     */
    public void onHighTemperatureExternalDisplayNotAllowed() {
        if (!mConnectedDisplayErrorHandlingEnabled) {
            Slog.d(TAG, "onHighTemperatureExternalDisplayNotAllowed:"
                                + " mConnectedDisplayErrorHandlingEnabled is false");
            return;
        }

        sendErrorNotification(createErrorNotification(
                R.string.connected_display_unavailable_notification_title,
                R.string.connected_display_thermally_unavailable_notification_content,
                R.drawable.ic_thermostat_notification));
    }

    /**
     * Cancel sent notifications.
     */
    public void cancelNotifications() {
        if (mNotificationManager == null) {
            Slog.e(TAG, "Can't cancelNotifications: NotificationManager is null");
            return;
        }

        mNotificationManager.cancel(DISPLAY_NOTIFICATION_TAG, DISPLAY_NOTIFICATION_ID);
    }

    /**
     * Send generic error notification.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void sendErrorNotification(final Notification notification) {
        if (mNotificationManager == null) {
            Slog.e(TAG, "Can't sendErrorNotification: NotificationManager is null");
            return;
        }

        mNotificationManager.notify(DISPLAY_NOTIFICATION_TAG, DISPLAY_NOTIFICATION_ID,
                notification);
    }

    /**
     * @return a newly built notification about an issue with connected display.
     */
    private Notification createErrorNotification(final int titleId, final int messageId,
            final int icon) {
        final Resources resources = mContext.getResources();
        final CharSequence title = resources.getText(titleId);
        final CharSequence message = resources.getText(messageId);

        int color = COLOR_DEFAULT;
        try (var attrs = mContext.obtainStyledAttributes(new int[]{R.attr.colorError})) {
            color = attrs.getColor(0, color);
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "colorError attribute is not found: " + e.getMessage());
        }

        return new Notification.Builder(mContext, ALERTS)
                .setGroup(NOTIFICATION_GROUP_NAME)
                .setSmallIcon(icon)
                .setWhen(0)
                .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLISEC)
                .setOngoing(false)
                .setTicker(title)
                .setColor(color)
                .setContentTitle(title)
                .setContentText(message)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }
}
