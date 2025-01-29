/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.security.advancedprotection.features;

import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_USER_PRESENT;
import static android.hardware.usb.UsbManager.ACTION_USB_PORT_CHANGED;
import static android.security.advancedprotection.AdvancedProtectionManager.FEATURE_ID_DISALLOW_USB;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Slog;

import java.lang.Runnable;

import java.util.function.Consumer;
import java.util.concurrent.Executor;

import android.security.advancedprotection.AdvancedProtectionFeature;

import com.android.internal.R;

import java.util.Map;

/**
 * AAPM Feature for managing and protecting USB data signal from attacks.
 *
 * @hide
 */
public class UsbDataAdvancedProtectionHook extends AdvancedProtectionHook {
    private static final String TAG = "AdvancedProtectionUsb";

    private static final String APM_USB_FEATURE_NOTIF_CHANNEL = "APM_USB_SERVICE_NOTIF_CHANNEL";
    private static final String CHANNEL_NAME = "BackgroundInstallUiNotificationChannel";
    private static final int APM_USB_FEATURE_CHANNEL_ID = 1;
    private static final int DELAY_DISABLE_MS = 1000;

    private final Context mContext;
    private final Handler mDelayedDisableHandler = new Handler(Looper.getMainLooper());

    private UsbManager mUsbManager;
    private BroadcastReceiver mUsbProtectionBroadcastReceiver;
    private KeyguardManager mKeyguardManager;
    private NotificationManager mNotificationManager;
    private NotificationChannel mNotificationChannel;

    private AdvancedProtectionFeature mFeature
        = new AdvancedProtectionFeature(FEATURE_ID_DISALLOW_USB);

    private boolean mBroadcastReceiverIsRegistered = false;
    private boolean mInitialPlugInNotificationSent = false;

    public UsbDataAdvancedProtectionHook(Context context, boolean enabled) {
        super(context, enabled);
        mContext = context;
        mUsbManager = mContext.getSystemService(UsbManager.class);
        onAdvancedProtectionChanged(enabled);
    }

    @Override
    public AdvancedProtectionFeature getFeature() {
        return mFeature;
    }

    @Override
    public boolean isAvailable() {
        return canSetUsbDataSignal();
    }

    @Override
    public void onAdvancedProtectionChanged(boolean enabled) {
        if (!isAvailable()) {
            Slog.w(TAG, "AAPM USB data protection feature is disabled");
            return;
        }
        Slog.i(TAG, "onAdvancedProtectionChanged: " + enabled);
        if (enabled) {
            Slog.i(TAG, "onAdvancedProtectionChanged: enabled");
            if (mUsbProtectionBroadcastReceiver == null) {
                initialize();
            }
            if (!mBroadcastReceiverIsRegistered) {
                registerReceiver();
            }
        } else {
            if (mBroadcastReceiverIsRegistered) {
                unregisterReceiver();
            }
            if (!mUsbManager.enableUsbDataSignal(true)) {
                Slog.e(TAG, "USB Data protection toggle failed");
            }
        }
    }

    private void initialize() {
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        initializeNotifications();
        mUsbProtectionBroadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            if (ACTION_USER_PRESENT.equals(intent.getAction())
                                    && !mKeyguardManager.isKeyguardLocked()) {
                                mDelayedDisableHandler.removeCallbacksAndMessages(null);
                                setUsbDataSignalIfNoConnectedDevices(true);
                            } else if (ACTION_SCREEN_OFF.equals(intent.getAction())
                                    && mKeyguardManager.isKeyguardLocked()) {
                                setUsbDataSignalIfNoConnectedDevices(false);
                            } else if (ACTION_USB_PORT_CHANGED.equals(intent.getAction())) {
                                if (Build.IS_DEBUGGABLE) {
                                    dumpUsbDevices();
                                }
                                setDelayedDisableTaskIfDisconnectedAndLocked(intent);
                                sendNotificationIfDeviceLocked(intent);
                            }
                        } catch (Exception e) {
                            Slog.e(TAG, "USB Data protection failed with: " + e.getMessage());
                        }
                    }

                    private boolean getUsbPortStatusIsConnectedAndDataEnabled(Intent intent) {
                        UsbPortStatus portStatus =
                                intent.getParcelableExtra(
                                        UsbManager.EXTRA_PORT_STATUS, UsbPortStatus.class);
                        return portStatus != null
                                && portStatus.isConnected()
                                && portStatus.getUsbDataStatus()
                                        != UsbPortStatus.DATA_STATUS_DISABLED_FORCE;
                    }

                    private void setDelayedDisableTaskIfDisconnectedAndLocked(Intent intent) {
                        if(mKeyguardManager.isKeyguardLocked()) {
                            if(getUsbPortStatusIsConnectedAndDataEnabled(intent)) {
                                mDelayedDisableHandler.removeCallbacksAndMessages(null);
                            } else if(!mDelayedDisableHandler.hasMessagesOrCallbacks()) {
                                mDelayedDisableHandler.postDelayed(() -> {
                                    disableChangedUsbPortIfDisconnected(intent);
                                }, DELAY_DISABLE_MS);
                            }
                        }
                    }

                    private void disableChangedUsbPortIfDisconnected(Intent intent) {
                        UsbPortStatus portStatus =
                                intent.getParcelableExtra(
                                        UsbManager.EXTRA_PORT_STATUS, UsbPortStatus.class);
                        if (Build.IS_DEBUGGABLE) {
                            Slog.i(
                                    TAG,
                                    "disableChangedUsbPortIfDisconnected: " + portStatus == null
                                            ? "null"
                                            : portStatus.toString());
                        }

                        if (mKeyguardManager.isKeyguardLocked()
                                && portStatus != null && !portStatus.isConnected()
                        ) {
                            intent.getParcelableExtra(
                                            UsbManager.EXTRA_PORT, ParcelableUsbPort.class)
                                    .getUsbPort(mUsbManager)
                                    .enableUsbData(false);
                        }
                    }

                    private void dumpUsbDevices() {
                        Slog.d(TAG, "dumpUsbDevices: ");
                        Map<String, UsbDevice> portStatusMap = mUsbManager.getDeviceList();
                        for (UsbDevice device : portStatusMap.values()) {
                            Slog.d(TAG, "Device: " + device.getDeviceName());
                        }
                        UsbAccessory[] accessoryList = mUsbManager.getAccessoryList();
                        if(accessoryList != null) {
                            for (UsbAccessory accessory : accessoryList) {
                                Slog.d(TAG, "Accessory: " + accessory.toString());
                            }
                        }
                    }
                };
    }

    private void initializeNotifications() {
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        if (mNotificationManager.getNotificationChannel(APM_USB_FEATURE_NOTIF_CHANNEL) == null) {
            mNotificationChannel =
                    new NotificationChannel(
                            APM_USB_FEATURE_NOTIF_CHANNEL,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_HIGH);
            mNotificationManager.createNotificationChannel(mNotificationChannel);
        }
    }

    private void sendNotification(String title, String message) {
        Notification notif =
                new Notification.Builder(mContext, APM_USB_FEATURE_NOTIF_CHANNEL)
                        .setSmallIcon(R.drawable.ic_settings_24dp)
                        .setContentTitle(title)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setAutoCancel(true)
                        .build();
        mNotificationManager.notify(TAG, APM_USB_FEATURE_CHANNEL_ID, notif);
    }

    private void sendNotificationIfDeviceLocked(Intent intent) {
        if (!mInitialPlugInNotificationSent) {
            UsbPortStatus portStatus =
                    intent.getParcelableExtra(UsbManager.EXTRA_PORT_STATUS, UsbPortStatus.class);
            if (mKeyguardManager.isKeyguardLocked()
                    && portStatus != null
                    && portStatus.isConnected()
                    && portStatus.getUsbDataStatus() == UsbPortStatus.DATA_STATUS_DISABLED_FORCE) {
                sendNotification(
                        mContext.getString(
                                R.string.usb_apm_usb_plugged_in_when_locked_notification_title),
                        mContext.getString(
                                R.string.usb_apm_usb_plugged_in_when_locked_notification_text));
                mInitialPlugInNotificationSent = true;
            }
        }
    }

    private void setUsbDataSignalIfNoConnectedDevices(boolean status) {
        // disable all ports that don't have an active data connection
        if (!status) {
            for (UsbPort usbPort : mUsbManager.getPorts()) {
                if (Build.IS_DEBUGGABLE) {
                    Slog.i(
                            TAG,
                            "setUsbDataSignal: false " + usbPort.getStatus() == null
                                    ? "null"
                                    : usbPort.getStatus().toString());
                }
                if (usbPort.getStatus() == null
                        || !usbPort.getStatus().isConnected()
                        || usbPort.getStatus().getCurrentDataRole()
                                == UsbPortStatus.DATA_ROLE_NONE) {
                    usbPort.enableUsbData(false);
                }
            }
        }
        // Always re-enable all if true
        else {
            if (!mUsbManager.enableUsbDataSignal(status)) {
                Slog.e(TAG, "USB Data protection toggle failed");
            }
            for (UsbPort usbPort : mUsbManager.getPorts()) {
                usbPort.resetUsbPort(mContext.getMainExecutor(),
                new Consumer<Integer>() {
                    public void accept(Integer status) {
                        Slog.i(TAG, "Consumer status: " + status);
                    }
                });
            }
        }
    }

    private void registerReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(ACTION_USER_PRESENT);
        filter.addAction(ACTION_SCREEN_OFF);
        filter.addAction(UsbManager.ACTION_USB_PORT_CHANGED);

        mContext.registerReceiverAsUser(
                mUsbProtectionBroadcastReceiver, UserHandle.ALL, filter, null, null);
        mBroadcastReceiverIsRegistered = true;
    }

    private void unregisterReceiver() {
        mContext.unregisterReceiver(mUsbProtectionBroadcastReceiver);
        mBroadcastReceiverIsRegistered = false;
    }

    private boolean canSetUsbDataSignal() {
        if (Build.IS_DEBUGGABLE) {
            Slog.i(TAG, "USB_HAL_VERSION: " + mUsbManager.getUsbHalVersion());
        }
        return mUsbManager.getUsbHalVersion() >= UsbManager.USB_HAL_V1_3;
    }
}
