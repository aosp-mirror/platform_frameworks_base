/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * UsbDeviceManager manages USB state in device mode.
 */
public class UsbDeviceManager {

    private static final String TAG = UsbDeviceManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String USB_STATE_MATCH =
            "DEVPATH=/devices/virtual/android_usb/android0";
    private static final String ACCESSORY_START_MATCH =
            "DEVPATH=/devices/virtual/misc/usb_accessory";
    private static final String FUNCTIONS_PATH =
            "/sys/class/android_usb/android0/functions";
    private static final String STATE_PATH =
            "/sys/class/android_usb/android0/state";
    private static final String MASS_STORAGE_FILE_PATH =
            "/sys/class/android_usb/android0/f_mass_storage/lun/file";
    private static final String RNDIS_ETH_ADDR_PATH =
            "/sys/class/android_usb/android0/f_rndis/ethaddr";

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_ENABLE_ADB = 1;
    private static final int MSG_SET_CURRENT_FUNCTION = 2;
    private static final int MSG_SYSTEM_READY = 3;
    private static final int MSG_BOOT_COMPLETED = 4;

    // Delay for debouncing USB disconnects.
    // We often get rapid connect/disconnect events when enabling USB functions,
    // which need debouncing.
    private static final int UPDATE_DELAY = 1000;

    private UsbHandler mHandler;
    private boolean mBootCompleted;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final UsbSettingsManager mSettingsManager;
    private NotificationManager mNotificationManager;
    private final boolean mHasUsbAccessory;
    private boolean mUseUsbNotification;
    private boolean mAdbEnabled;

    private class AdbSettingsObserver extends ContentObserver {
        public AdbSettingsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            boolean enable = (Settings.Secure.getInt(mContentResolver,
                    Settings.Secure.ADB_ENABLED, 0) > 0);
            mHandler.sendMessage(MSG_ENABLE_ADB, enable);
        }
    }

    /*
     * Listens for uevent messages from the kernel to monitor the USB state
     */
    private final UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            if (DEBUG) Slog.v(TAG, "USB UEVENT: " + event.toString());

            String state = event.get("USB_STATE");
            String accessory = event.get("ACCESSORY");
            if (state != null) {
                mHandler.updateState(state);
            } else if ("START".equals(accessory)) {
                if (DEBUG) Slog.d(TAG, "got accessory start");
                setCurrentFunction(UsbManager.USB_FUNCTION_ACCESSORY, false);
            }
        }
    };

    public UsbDeviceManager(Context context, UsbSettingsManager settingsManager) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mSettingsManager = settingsManager;
        PackageManager pm = mContext.getPackageManager();
        mHasUsbAccessory = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);
        initRndisAddress();

        // create a thread for our Handler
        HandlerThread thread = new HandlerThread("UsbDeviceManager",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new UsbHandler(thread.getLooper());

        if (nativeIsStartRequested()) {
            if (DEBUG) Slog.d(TAG, "accessory attached at boot");
            setCurrentFunction(UsbManager.USB_FUNCTION_ACCESSORY, false);
        }
    }

    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        // We do not show the USB notification if the primary volume supports mass storage.
        // The legacy mass storage UI will be used instead.
        boolean massStorageSupported = false;
        StorageManager storageManager = (StorageManager)
                mContext.getSystemService(Context.STORAGE_SERVICE);
        StorageVolume[] volumes = storageManager.getVolumeList();
        if (volumes.length > 0) {
            massStorageSupported = volumes[0].allowMassStorage();
        }
        mUseUsbNotification = !massStorageSupported;

        // make sure the ADB_ENABLED setting value matches the current state
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ADB_ENABLED, mAdbEnabled ? 1 : 0);

        mHandler.sendEmptyMessage(MSG_SYSTEM_READY);
    }

    private static void initRndisAddress() {
        // configure RNDIS ethernet address based on our serial number using the same algorithm
        // we had been previously using in kernel board files
        final int ETH_ALEN = 6;
        int address[] = new int[ETH_ALEN];
        // first byte is 0x02 to signify a locally administered address
        address[0] = 0x02;

        String serial = SystemProperties.get("ro.serialno", "1234567890ABCDEF");
        int serialLength = serial.length();
        // XOR the USB serial across the remaining 5 bytes
        for (int i = 0; i < serialLength; i++) {
            address[i % (ETH_ALEN - 1) + 1] ^= (int)serial.charAt(i);
        }
        String addrString = String.format("%02X:%02X:%02X:%02X:%02X:%02X",
            address[0], address[1], address[2], address[3], address[4], address[5]);
        try {
            FileUtils.stringToFile(RNDIS_ETH_ADDR_PATH, addrString);
        } catch (IOException e) {
           Slog.e(TAG, "failed to write to " + RNDIS_ETH_ADDR_PATH);
        }
    }

     private static String addFunction(String functions, String function) {
        if (!containsFunction(functions, function)) {
            if (functions.length() > 0) {
                functions += ",";
            }
            functions += function;
        }
        return functions;
    }

    private static String removeFunction(String functions, String function) {
        String[] split = functions.split(",");
        for (int i = 0; i < split.length; i++) {
            if (function.equals(split[i])) {
                split[i] = null;
            }
        }
        StringBuilder builder = new StringBuilder();
         for (int i = 0; i < split.length; i++) {
            String s = split[i];
            if (s != null) {
                if (builder.length() > 0) {
                    builder.append(",");
                }
                builder.append(s);
            }
        }
        return builder.toString();
    }

    private static boolean containsFunction(String functions, String function) {
        int index = functions.indexOf(function);
        if (index < 0) return false;
        if (index > 0 && functions.charAt(index - 1) != ',') return false;
        int charAfter = index + function.length();
        if (charAfter < functions.length() && functions.charAt(charAfter) != ',') return false;
        return true;
    }

    private final class UsbHandler extends Handler {

        // current USB state
        private boolean mConnected;
        private boolean mConfigured;
        private String mCurrentFunctions;
        private String mDefaultFunctions;
        private UsbAccessory mCurrentAccessory;
        private int mUsbNotificationId;
        private boolean mAdbNotificationShown;

        private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (DEBUG) Slog.d(TAG, "boot completed");
                mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
            }
        };

        private static final int NOTIFICATION_NONE = 0;
        private static final int NOTIFICATION_MTP = 1;
        private static final int NOTIFICATION_PTP = 2;
        private static final int NOTIFICATION_INSTALLER = 3;
        private static final int NOTIFICATION_ADB = 4;

        public UsbHandler(Looper looper) {
            super(looper);
            try {
                // persist.sys.usb.config should never be unset.  But if it is, set it to "adb"
                // so we have a chance of debugging what happened.
                mDefaultFunctions = SystemProperties.get("persist.sys.usb.config", "adb");
                // sanity check the sys.usb.config system property
                // this may be necessary if we crashed while switching USB configurations
                String config = SystemProperties.get("sys.usb.config", "none");
                if (!config.equals(mDefaultFunctions)) {
                    Slog.w(TAG, "resetting config to persistent property: " + mDefaultFunctions);
                    SystemProperties.set("sys.usb.config", mDefaultFunctions);
                }

                mCurrentFunctions = mDefaultFunctions;
                String state = FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim();
                updateState(state);
                mAdbEnabled = containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_ADB);

                // Upgrade step for previous versions that used persist.service.adb.enable
                String value = SystemProperties.get("persist.service.adb.enable", "");
                if (value.length() > 0) {
                    char enable = value.charAt(0);
                    if (enable == '1') {
                        setAdbEnabled(true);
                    } else if (enable == '0') {
                        setAdbEnabled(false);
                    }
                    SystemProperties.set("persist.service.adb.enable", "");
                }

                // register observer to listen for settings changes
                mContentResolver.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ADB_ENABLED),
                                false, new AdbSettingsObserver());

                // Watch for USB configuration changes
                mUEventObserver.startObserving(USB_STATE_MATCH);
                mUEventObserver.startObserving(ACCESSORY_START_MATCH);

                mContext.registerReceiver(mBootCompletedReceiver,
                        new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
            } catch (Exception e) {
                Slog.e(TAG, "Error initializing UsbHandler", e);
            }
        }

        public void sendMessage(int what, boolean arg) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.arg1 = (arg ? 1 : 0);
            sendMessage(m);
        }

        public void sendMessage(int what, Object arg) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.obj = arg;
            sendMessage(m);
        }

        public void sendMessage(int what, Object arg0, boolean arg1) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.obj = arg0;
            m.arg1 = (arg1 ? 1 : 0);
            sendMessage(m);
        }

        public void updateState(String state) {
            int connected, configured;

            if ("DISCONNECTED".equals(state)) {
                connected = 0;
                configured = 0;
            } else if ("CONNECTED".equals(state)) {
                connected = 1;
                configured = 0;
            } else if ("CONFIGURED".equals(state)) {
                connected = 1;
                configured = 1;
            } else {
                Slog.e(TAG, "unknown state " + state);
                return;
            }
            removeMessages(MSG_UPDATE_STATE);
            Message msg = Message.obtain(this, MSG_UPDATE_STATE);
            msg.arg1 = connected;
            msg.arg2 = configured;
            // debounce disconnects to avoid problems bringing up USB tethering
            sendMessageDelayed(msg, (connected == 0) ? UPDATE_DELAY : 0);
        }

        private boolean waitForState(String state) {
            // wait for the transition to complete.
            // give up after 1 second.
            for (int i = 0; i < 20; i++) {
                // State transition is done when sys.usb.conf.done is set to the new configuration
                if (state.equals(SystemProperties.get("sys.usb.state"))) return true;
                try {
                    // try again in 50ms
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
            }
            Slog.e(TAG, "waitForState(" + state + ") FAILED");
            return false;
        }

        private boolean setUsbConfig(String config) {
            if (DEBUG) Slog.d(TAG, "setUsbConfig(" + config + ")");
            // set the new configuration
            SystemProperties.set("sys.usb.config", config);
            return waitForState(config);
        }

        private void doSetCurrentFunctions(String functions) {
            if (!mCurrentFunctions.equals(functions)) {
                if (!setUsbConfig("none") || !setUsbConfig(functions)) {
                    Slog.e(TAG, "Failed to switch USB configuration to " + functions);
                    // revert to previous configuration if we fail
                    setUsbConfig(mCurrentFunctions);
                } else {
                    mCurrentFunctions = functions;
                }
            }
        }

        private void setAdbEnabled(boolean enable) {
            if (DEBUG) Slog.d(TAG, "setAdbEnabled: " + enable);
            if (enable != mAdbEnabled) {
                mAdbEnabled = enable;
                String functions;
                // Due to the persist.sys.usb.config property trigger, changing adb state requires
                // switching to default function
                if (enable) {
                    functions = addFunction(mDefaultFunctions, UsbManager.USB_FUNCTION_ADB);
                } else {
                    functions = removeFunction(mDefaultFunctions, UsbManager.USB_FUNCTION_ADB);
                }
                setCurrentFunction(functions, true);
                updateAdbNotification();
            }
        }

        private void setEnabledFunctions(String functionList) {
            if (mAdbEnabled) {
                functionList = addFunction(functionList, UsbManager.USB_FUNCTION_ADB);
            } else {
                functionList = removeFunction(functionList, UsbManager.USB_FUNCTION_ADB);
            }
            doSetCurrentFunctions(functionList);
        }

        private void updateCurrentAccessory() {
            if (!mHasUsbAccessory) return;

            if (mConfigured) {
                String[] strings = nativeGetAccessoryStrings();
                if (strings != null) {
                    mCurrentAccessory = new UsbAccessory(strings);
                    Slog.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                    // defer accessoryAttached if system is not ready
                    if (mBootCompleted) {
                        mSettingsManager.accessoryAttached(mCurrentAccessory);
                    } // else handle in mBootCompletedReceiver
                } else {
                    Slog.e(TAG, "nativeGetAccessoryStrings failed");
                }
            } else if (!mConnected) {
                // make sure accessory mode is off
                // and restore default functions
                Slog.d(TAG, "exited USB accessory mode");
                setEnabledFunctions(mDefaultFunctions);

                if (mCurrentAccessory != null) {
                    if (mBootCompleted) {
                        mSettingsManager.accessoryDetached(mCurrentAccessory);
                    }
                    mCurrentAccessory = null;
                }
            }
        }

        private void updateUsbState() {
            // send a sticky broadcast containing current USB state
            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(UsbManager.USB_CONNECTED, mConnected);
            intent.putExtra(UsbManager.USB_CONFIGURED, mConfigured);

            if (mCurrentFunctions != null) {
                String[] functions = mCurrentFunctions.split(",");
                for (int i = 0; i < functions.length; i++) {
                    intent.putExtra(functions[i], true);
                }
            }

            mContext.sendStickyBroadcast(intent);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_STATE:
                    mConnected = (msg.arg1 == 1);
                    mConfigured = (msg.arg2 == 1);
                    updateUsbNotification();
                    updateAdbNotification();
                    if (containsFunction(mCurrentFunctions,
                            UsbManager.USB_FUNCTION_ACCESSORY)) {
                        updateCurrentAccessory();
                    }

                    if (!mConnected) {
                        // restore defaults when USB is disconnected
                        doSetCurrentFunctions(mDefaultFunctions);
                    }
                    if (mBootCompleted) {
                        updateUsbState();
                    }
                    break;
                case MSG_ENABLE_ADB:
                    setAdbEnabled(msg.arg1 == 1);
                    break;
                case MSG_SET_CURRENT_FUNCTION:
                    String function = (String)msg.obj;
                    boolean makeDefault = (msg.arg1 == 1);
                    if (function != null && makeDefault) {
                        if (mAdbEnabled) {
                            function = addFunction(function, UsbManager.USB_FUNCTION_ADB);
                        }

                        setUsbConfig("none");
                        // setting this property will change the current USB state
                        // via a property trigger
                        SystemProperties.set("persist.sys.usb.config", function);
                        if (waitForState(function)) {
                            mCurrentFunctions = function;
                            mDefaultFunctions = function;
                        }
                    } else {
                        if (function == null) {
                            function = mDefaultFunctions;
                        }
                        setEnabledFunctions(function);
                    }
                    break;
                case MSG_SYSTEM_READY:
                    updateUsbNotification();
                    updateAdbNotification();
                    updateUsbState();
                    break;
                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    if (mCurrentAccessory != null) {
                        mSettingsManager.accessoryAttached(mCurrentAccessory);
                    }
                    break;
            }
        }

        public UsbAccessory getCurrentAccessory() {
            return mCurrentAccessory;
        }

        private void updateUsbNotification() {
            if (mNotificationManager == null || !mUseUsbNotification) return;
            if (mConnected) {
                Resources r = mContext.getResources();
                CharSequence title = null;
                int id = NOTIFICATION_NONE;
                if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MTP)) {
                    title = r.getText(
                        com.android.internal.R.string.usb_mtp_notification_title);
                    id = NOTIFICATION_MTP;
                } else if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_PTP)) {
                    title = r.getText(
                        com.android.internal.R.string.usb_ptp_notification_title);
                    id = NOTIFICATION_PTP;
                } else if (containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_MASS_STORAGE)) {
                    title = r.getText(
                        com.android.internal.R.string.usb_cd_installer_notification_title);
                    id = NOTIFICATION_INSTALLER;
                } else {
                    Slog.e(TAG, "No known USB function in updateUsbNotification");
                }
                if (id != mUsbNotificationId) {
                    // clear notification if title needs changing
                    if (mUsbNotificationId != NOTIFICATION_NONE) {
                        mNotificationManager.cancel(mUsbNotificationId);
                        mUsbNotificationId = NOTIFICATION_NONE;
                    }
                }
                if (mUsbNotificationId == NOTIFICATION_NONE) {
                    CharSequence message = r.getText(
                            com.android.internal.R.string.usb_notification_message);

                    Notification notification = new Notification();
                    notification.icon = com.android.internal.R.drawable.stat_sys_data_usb;
                    notification.when = 0;
                    notification.flags = Notification.FLAG_ONGOING_EVENT;
                    notification.tickerText = title;
                    notification.defaults = 0; // please be quiet
                    notification.sound = null;
                    notification.vibrate = null;

                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    intent.setComponent(new ComponentName("com.android.settings",
                            "com.android.settings.UsbSettings"));
                    PendingIntent pi = PendingIntent.getActivity(mContext, 0,
                            intent, 0);
                    notification.setLatestEventInfo(mContext, title, message, pi);
                    mNotificationManager.notify(id, notification);
                    mUsbNotificationId = id;
                }

            } else if (mUsbNotificationId != NOTIFICATION_NONE) {
                mNotificationManager.cancel(mUsbNotificationId);
                mUsbNotificationId = NOTIFICATION_NONE;
            }
        }

        private void updateAdbNotification() {
            if (mNotificationManager == null) return;
            if (mAdbEnabled && mConnected) {
                if ("0".equals(SystemProperties.get("persist.adb.notify"))) return;

                if (!mAdbNotificationShown) {
                    Resources r = mContext.getResources();
                    CharSequence title = r.getText(
                            com.android.internal.R.string.adb_active_notification_title);
                    CharSequence message = r.getText(
                            com.android.internal.R.string.adb_active_notification_message);

                    Notification notification = new Notification();
                    notification.icon = com.android.internal.R.drawable.stat_sys_adb;
                    notification.when = 0;
                    notification.flags = Notification.FLAG_ONGOING_EVENT;
                    notification.tickerText = title;
                    notification.defaults = 0; // please be quiet
                    notification.sound = null;
                    notification.vibrate = null;

                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    intent.setComponent(new ComponentName("com.android.settings",
                            "com.android.settings.DevelopmentSettings"));
                    PendingIntent pi = PendingIntent.getActivity(mContext, 0,
                            intent, 0);
                    notification.setLatestEventInfo(mContext, title, message, pi);
                    mAdbNotificationShown = true;
                    mNotificationManager.notify(NOTIFICATION_ADB, notification);
                }
            } else if (mAdbNotificationShown) {
                mAdbNotificationShown = false;
                mNotificationManager.cancel(NOTIFICATION_ADB);
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw) {
            pw.println("  USB Device State:");
            pw.println("    Current Functions: " + mCurrentFunctions);
            pw.println("    Default Functions: " + mDefaultFunctions);
            pw.println("    mConnected: " + mConnected);
            pw.println("    mConfigured: " + mConfigured);
            pw.println("    mCurrentAccessory: " + mCurrentAccessory);
            try {
                pw.println("    Kernel state: "
                        + FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim());
                pw.println("    Kernel function list: "
                        + FileUtils.readTextFile(new File(FUNCTIONS_PATH), 0, null).trim());
                pw.println("    Mass storage backing file: "
                        + FileUtils.readTextFile(new File(MASS_STORAGE_FILE_PATH), 0, null).trim());
            } catch (IOException e) {
                pw.println("IOException: " + e);
            }
        }
    }

    /* returns the currently attached USB accessory */
    public UsbAccessory getCurrentAccessory() {
        return mHandler.getCurrentAccessory();
    }

    /* opens the currently attached USB accessory */
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        UsbAccessory currentAccessory = mHandler.getCurrentAccessory();
        if (currentAccessory == null) {
            throw new IllegalArgumentException("no accessory attached");
        }
        if (!currentAccessory.equals(accessory)) {
            String error = accessory.toString()
                    + " does not match current accessory "
                    + currentAccessory;
            throw new IllegalArgumentException(error);
        }
        mSettingsManager.checkPermission(accessory);
        return nativeOpenAccessory();
    }

    public void setCurrentFunction(String function, boolean makeDefault) {
        if (DEBUG) Slog.d(TAG, "setCurrentFunction(" + function + ") default: " + makeDefault);
        mHandler.sendMessage(MSG_SET_CURRENT_FUNCTION, function, makeDefault);
    }

    public void setMassStorageBackingFile(String path) {
        if (path == null) path = "";
        try {
            FileUtils.stringToFile(MASS_STORAGE_FILE_PATH, path);
        } catch (IOException e) {
           Slog.e(TAG, "failed to write to " + MASS_STORAGE_FILE_PATH);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw) {
        if (mHandler != null) {
            mHandler.dump(fd, pw);
        }
    }

    private native String[] nativeGetAccessoryStrings();
    private native ParcelFileDescriptor nativeOpenAccessory();
    private native boolean nativeIsStartRequested();
}
