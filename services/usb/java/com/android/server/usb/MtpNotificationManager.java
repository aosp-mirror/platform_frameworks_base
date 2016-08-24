/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.usb;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.UserHandle;

/**
 * Manager for MTP storage notification.
 */
class MtpNotificationManager {
    private static final String TAG = "UsbMtpNotificationManager";

    /**
     * Subclass for PTP.
     */
    private static final int SUBCLASS_STILL_IMAGE_CAPTURE = 1;

    /**
     * Subclass for Android style MTP.
     */
    private static final int SUBCLASS_MTP = 0xff;

    /**
     * Protocol for Picture Transfer Protocol (PIMA 15470).
     */
    private static final int PROTOCOL_PTP = 1;

    /**
     * Protocol for Android style MTP.
     */
    private static final int PROTOCOL_MTP = 0;

    private static final String ACTION_OPEN_IN_APPS = "com.android.server.usb.ACTION_OPEN_IN_APPS";

    private final Context mContext;
    private final OnOpenInAppListener mListener;

    MtpNotificationManager(Context context, OnOpenInAppListener listener) {
        mContext = context;
        mListener = listener;
        final Receiver receiver = new Receiver();
        context.registerReceiver(receiver, new IntentFilter(ACTION_OPEN_IN_APPS));
    }

    void showNotification(UsbDevice device) {
        final Resources resources = mContext.getResources();
        final String title = resources.getString(
                com.android.internal.R.string.usb_mtp_launch_notification_title,
                device.getProductName());
        final String description = resources.getString(
                com.android.internal.R.string.usb_mtp_launch_notification_description);
        final Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle(title)
                .setContentText(description)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_data_usb)
                .setCategory(Notification.CATEGORY_SYSTEM);

        final Intent intent = new Intent(ACTION_OPEN_IN_APPS);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);

        final PendingIntent openIntent = PendingIntent.getBroadcastAsUser(
                mContext,
                device.getDeviceId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                UserHandle.SYSTEM);
        builder.setContentIntent(openIntent);

        final Notification notification = builder.build();
        notification.flags |= Notification.FLAG_LOCAL_ONLY;

        mContext.getSystemService(NotificationManager.class).notify(
                TAG, device.getDeviceId(), notification);
    }

    void hideNotification(int deviceId) {
        mContext.getSystemService(NotificationManager.class).cancel(TAG, deviceId);
    }

    private class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final UsbDevice device =
                    intent.getExtras().<UsbDevice>getParcelable(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            switch (intent.getAction()) {
                case ACTION_OPEN_IN_APPS:
                    mListener.onOpenInApp(device);
                    break;
            }
        }
    }

    static boolean shouldShowNotification(PackageManager packageManager, UsbDevice device) {
        // We don't show MTP notification for devices that has FEATURE_AUTOMOTIVE.
        return !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                isMtpDevice(device);
    }

    private static boolean isMtpDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            final UsbInterface usbInterface = device.getInterface(i);
            if ((usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    usbInterface.getInterfaceSubclass() == SUBCLASS_STILL_IMAGE_CAPTURE &&
                    usbInterface.getInterfaceProtocol() == PROTOCOL_PTP)) {
                return true;
            }
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_SUBCLASS_VENDOR_SPEC &&
                    usbInterface.getInterfaceSubclass() == SUBCLASS_MTP &&
                    usbInterface.getInterfaceProtocol() == PROTOCOL_MTP &&
                    "MTP".equals(usbInterface.getName())) {
                return true;
            }
        }
        return false;
    }

    static interface OnOpenInAppListener {
        void onOpenInApp(UsbDevice device);
    }
}
