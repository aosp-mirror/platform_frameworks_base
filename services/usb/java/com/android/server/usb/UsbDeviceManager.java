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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.BatteryManager;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.FgThread;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * UsbDeviceManager manages USB state in device mode.
 */
public class UsbDeviceManager {

    private static final String TAG = "UsbDeviceManager";
    private static final boolean DEBUG = false;

    /**
     * The persistent property which stores whether adb is enabled or not.
     * May also contain vendor-specific default functions for testing purposes.
     */
    private static final String USB_PERSISTENT_CONFIG_PROPERTY = "persist.sys.usb.config";

    /**
     * The non-persistent property which stores the current USB settings.
     */
    private static final String USB_CONFIG_PROPERTY = "sys.usb.config";

    /**
     * The non-persistent property which stores the current USB actual state.
     */
    private static final String USB_STATE_PROPERTY = "sys.usb.state";

    /**
     * ro.bootmode value when phone boots into usual Android.
     */
    private static final String NORMAL_BOOT = "normal";

    private static final String USB_STATE_MATCH =
            "DEVPATH=/devices/virtual/android_usb/android0";
    private static final String ACCESSORY_START_MATCH =
            "DEVPATH=/devices/virtual/misc/usb_accessory";
    private static final String FUNCTIONS_PATH =
            "/sys/class/android_usb/android0/functions";
    private static final String STATE_PATH =
            "/sys/class/android_usb/android0/state";
    private static final String RNDIS_ETH_ADDR_PATH =
            "/sys/class/android_usb/android0/f_rndis/ethaddr";
    private static final String AUDIO_SOURCE_PCM_PATH =
            "/sys/class/android_usb/android0/f_audio_source/pcm";
    private static final String MIDI_ALSA_PATH =
            "/sys/class/android_usb/android0/f_midi/alsa";

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_ENABLE_ADB = 1;
    private static final int MSG_SET_CURRENT_FUNCTIONS = 2;
    private static final int MSG_SYSTEM_READY = 3;
    private static final int MSG_BOOT_COMPLETED = 4;
    private static final int MSG_USER_SWITCHED = 5;
    private static final int MSG_UPDATE_USER_RESTRICTIONS = 6;
    private static final int MSG_UPDATE_PORT_STATE = 7;
    private static final int MSG_ACCESSORY_MODE_ENTER_TIMEOUT = 8;
    private static final int MSG_UPDATE_CHARGING_STATE = 9;
    private static final int MSG_UPDATE_HOST_STATE = 10;
    private static final int MSG_LOCALE_CHANGED = 11;

    private static final int AUDIO_MODE_SOURCE = 1;

    // Delay for debouncing USB disconnects.
    // We often get rapid connect/disconnect events when enabling USB functions,
    // which need debouncing.
    private static final int UPDATE_DELAY = 1000;

    // Timeout for entering USB request mode.
    // Request is cancelled if host does not configure device within 10 seconds.
    private static final int ACCESSORY_REQUEST_TIMEOUT = 10 * 1000;

    private static final String BOOT_MODE_PROPERTY = "ro.bootmode";

    private static final String ADB_NOTIFICATION_CHANNEL_ID_TV = "usbdevicemanager.adb.tv";

    private UsbHandler mHandler;
    private boolean mBootCompleted;

    private final Object mLock = new Object();

    private final Context mContext;
    private final ContentResolver mContentResolver;
    @GuardedBy("mLock")
    private UsbProfileGroupSettingsManager mCurrentSettings;
    private NotificationManager mNotificationManager;
    private final boolean mHasUsbAccessory;
    private boolean mUseUsbNotification;
    private boolean mAdbEnabled;
    private boolean mAudioSourceEnabled;
    private boolean mMidiEnabled;
    private int mMidiCard;
    private int mMidiDevice;
    private HashMap<String, HashMap<String, Pair<String, String>>> mOemModeMap;
    private String[] mAccessoryStrings;
    private UsbDebuggingManager mDebuggingManager;
    private final UsbAlsaManager mUsbAlsaManager;
    private final UsbSettingsManager mSettingsManager;
    private Intent mBroadcastedIntent;
    private boolean mPendingBootBroadcast;
    private static Set<Integer> sBlackListedInterfaces;

    static {
        sBlackListedInterfaces = new HashSet<>();
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_AUDIO);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_COMM);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_HID);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_PRINTER);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_MASS_STORAGE);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_HUB);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_CDC_DATA);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_CSCID);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_CONTENT_SEC);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_VIDEO);
        sBlackListedInterfaces.add(UsbConstants.USB_CLASS_WIRELESS_CONTROLLER);
    }

    private class AdbSettingsObserver extends ContentObserver {
        public AdbSettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean enable = (Settings.Global.getInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, 0) > 0);
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
                startAccessoryMode();
            }
        }
    };

    public UsbDeviceManager(Context context, UsbAlsaManager alsaManager,
            UsbSettingsManager settingsManager) {
        mContext = context;
        mUsbAlsaManager = alsaManager;
        mSettingsManager = settingsManager;
        mContentResolver = context.getContentResolver();
        PackageManager pm = mContext.getPackageManager();
        mHasUsbAccessory = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);
        initRndisAddress();

        readOemUsbOverrideConfig();

        mHandler = new UsbHandler(FgThread.get().getLooper());

        if (nativeIsStartRequested()) {
            if (DEBUG) Slog.d(TAG, "accessory attached at boot");
            startAccessoryMode();
        }

        boolean secureAdbEnabled = SystemProperties.getBoolean("ro.adb.secure", false);
        boolean dataEncrypted = "1".equals(SystemProperties.get("vold.decrypt"));
        if (secureAdbEnabled && !dataEncrypted) {
            mDebuggingManager = new UsbDebuggingManager(context);
        }

        BroadcastReceiver portReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                UsbPort port = intent.getParcelableExtra(UsbManager.EXTRA_PORT);
                UsbPortStatus status = intent.getParcelableExtra(UsbManager.EXTRA_PORT_STATUS);
                mHandler.updateHostState(port, status);
            }
        };

        BroadcastReceiver chargingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                boolean usbCharging = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
                mHandler.sendMessage(MSG_UPDATE_CHARGING_STATE, usbCharging);
            }
        };

        BroadcastReceiver hostReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Iterator devices = ((UsbManager) context.getSystemService(Context.USB_SERVICE))
                        .getDeviceList().entrySet().iterator();
                if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    mHandler.sendMessage(MSG_UPDATE_HOST_STATE, devices, true);
                } else {
                    mHandler.sendMessage(MSG_UPDATE_HOST_STATE, devices, false);
                }
            }
        };

        BroadcastReceiver languageChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mHandler.sendEmptyMessage(MSG_LOCALE_CHANGED);
            }
        };

        mContext.registerReceiver(portReceiver,
                new IntentFilter(UsbManager.ACTION_USB_PORT_CHANGED));
        mContext.registerReceiver(chargingReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        IntentFilter filter =
                new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mContext.registerReceiver(hostReceiver, filter);

        mContext.registerReceiver(languageChangedReceiver,
                new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
    }

    private UsbProfileGroupSettingsManager getCurrentSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
        }
    }

    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        // Ensure that the notification channels are set up
        if (isTv()) {
            // TV-specific notification channel
            mNotificationManager.createNotificationChannel(
                    new NotificationChannel(ADB_NOTIFICATION_CHANNEL_ID_TV,
                            mContext.getString(
                                    com.android.internal.R.string
                                            .adb_debugging_notification_channel_tv),
                            NotificationManager.IMPORTANCE_HIGH));
        }

        // We do not show the USB notification if the primary volume supports mass storage.
        // The legacy mass storage UI will be used instead.
        boolean massStorageSupported;
        final StorageManager storageManager = StorageManager.from(mContext);
        final StorageVolume primary = storageManager.getPrimaryVolume();
        massStorageSupported = primary != null && primary.allowMassStorage();
        mUseUsbNotification = !massStorageSupported && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_usbChargingMessage);

        // make sure the ADB_ENABLED setting value matches the current state
        try {
            Settings.Global.putInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, mAdbEnabled ? 1 : 0);
        } catch (SecurityException e) {
            // If UserManager.DISALLOW_DEBUGGING_FEATURES is on, that this setting can't be changed.
            Slog.d(TAG, "ADB_ENABLED is restricted.");
        }
        mHandler.sendEmptyMessage(MSG_SYSTEM_READY);
    }

    public void bootCompleted() {
        if (DEBUG) Slog.d(TAG, "boot completed");
        mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
    }

    public void setCurrentUser(int newCurrentUserId, UsbProfileGroupSettingsManager settings) {
        synchronized (mLock) {
            mCurrentSettings = settings;
            mHandler.obtainMessage(MSG_USER_SWITCHED, newCurrentUserId, 0).sendToTarget();
        }
    }

    public void updateUserRestrictions() {
        mHandler.sendEmptyMessage(MSG_UPDATE_USER_RESTRICTIONS);
    }

    private void startAccessoryMode() {
        if (!mHasUsbAccessory) return;

        mAccessoryStrings = nativeGetAccessoryStrings();
        boolean enableAudio = (nativeGetAudioMode() == AUDIO_MODE_SOURCE);
        // don't start accessory mode if our mandatory strings have not been set
        boolean enableAccessory = (mAccessoryStrings != null &&
                mAccessoryStrings[UsbAccessory.MANUFACTURER_STRING] != null &&
                mAccessoryStrings[UsbAccessory.MODEL_STRING] != null);
        String functions = null;

        if (enableAccessory && enableAudio) {
            functions = UsbManager.USB_FUNCTION_ACCESSORY + ","
                    + UsbManager.USB_FUNCTION_AUDIO_SOURCE;
        } else if (enableAccessory) {
            functions = UsbManager.USB_FUNCTION_ACCESSORY;
        } else if (enableAudio) {
            functions = UsbManager.USB_FUNCTION_AUDIO_SOURCE;
        }

        if (functions != null) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_ACCESSORY_MODE_ENTER_TIMEOUT),
                    ACCESSORY_REQUEST_TIMEOUT);
            setCurrentFunctions(functions, false);
        }
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
            address[i % (ETH_ALEN - 1) + 1] ^= (int) serial.charAt(i);
        }
        String addrString = String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                address[0], address[1], address[2], address[3], address[4], address[5]);
        try {
            FileUtils.stringToFile(RNDIS_ETH_ADDR_PATH, addrString);
        } catch (IOException e) {
            Slog.e(TAG, "failed to write to " + RNDIS_ETH_ADDR_PATH);
        }
    }

    private boolean isTv() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private final class UsbHandler extends Handler {

        // current USB state
        private boolean mConnected;
        private boolean mHostConnected;
        private boolean mSourcePower;
        private boolean mSinkPower;
        private boolean mConfigured;
        private boolean mUsbDataUnlocked;
        private boolean mAudioAccessoryConnected;
        private boolean mAudioAccessorySupported;
        private String mCurrentFunctions;
        private boolean mCurrentFunctionsApplied;
        private UsbAccessory mCurrentAccessory;
        private int mUsbNotificationId;
        private boolean mAdbNotificationShown;
        private int mCurrentUser = UserHandle.USER_NULL;
        private boolean mUsbCharging;
        private String mCurrentOemFunctions;
        private boolean mHideUsbNotification;
        private boolean mSupportsAllCombinations;

        public UsbHandler(Looper looper) {
            super(looper);
            try {
                // Restore default functions.

                if (isNormalBoot()) {
                    mCurrentFunctions = SystemProperties.get(USB_CONFIG_PROPERTY,
                            UsbManager.USB_FUNCTION_NONE);
                    mCurrentFunctionsApplied = mCurrentFunctions.equals(
                            SystemProperties.get(USB_STATE_PROPERTY));
                } else {
                    mCurrentFunctions = SystemProperties.get(getPersistProp(true),
                            UsbManager.USB_FUNCTION_NONE);
                    mCurrentFunctionsApplied = SystemProperties.get(USB_CONFIG_PROPERTY,
                            UsbManager.USB_FUNCTION_NONE).equals(
                            SystemProperties.get(USB_STATE_PROPERTY));
                }

                /*
                 * Use the normal bootmode persistent prop to maintain state of adb across
                 * all boot modes.
                 */
                mAdbEnabled = UsbManager.containsFunction(
                        SystemProperties.get(USB_PERSISTENT_CONFIG_PROPERTY),
                        UsbManager.USB_FUNCTION_ADB);

                /*
                 * Previous versions can set persist config to mtp/ptp but it does not
                 * get reset on OTA. Reset the property here instead.
                 */
                String persisted = SystemProperties.get(USB_PERSISTENT_CONFIG_PROPERTY);
                if (UsbManager.containsFunction(persisted, UsbManager.USB_FUNCTION_MTP)
                        || UsbManager.containsFunction(persisted, UsbManager.USB_FUNCTION_PTP)) {
                    SystemProperties.set(USB_PERSISTENT_CONFIG_PROPERTY,
                            UsbManager.removeFunction(UsbManager.removeFunction(persisted,
                                    UsbManager.USB_FUNCTION_MTP), UsbManager.USB_FUNCTION_PTP));
                }

                String state = FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim();
                updateState(state);

                // register observer to listen for settings changes
                mContentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                        false, new AdbSettingsObserver());

                // Watch for USB configuration changes
                mUEventObserver.startObserving(USB_STATE_MATCH);
                mUEventObserver.startObserving(ACCESSORY_START_MATCH);
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

        public void sendMessage(int what, Object arg, boolean arg1) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.obj = arg;
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

        public void updateHostState(UsbPort port, UsbPortStatus status) {
            if (DEBUG) {
                Slog.i(TAG, "updateHostState " + port + " status=" + status);
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = port;
            args.arg2 = status;

            removeMessages(MSG_UPDATE_PORT_STATE);
            Message msg = obtainMessage(MSG_UPDATE_PORT_STATE, args);
            // debounce rapid transitions of connect/disconnect on type-c ports
            sendMessageDelayed(msg, UPDATE_DELAY);
        }

        private boolean waitForState(String state) {
            // wait for the transition to complete.
            // give up after 1 second.
            String value = null;
            for (int i = 0; i < 20; i++) {
                // State transition is done when sys.usb.state is set to the new configuration
                value = SystemProperties.get(USB_STATE_PROPERTY);
                if (state.equals(value)) return true;
                SystemClock.sleep(50);
            }
            Slog.e(TAG, "waitForState(" + state + ") FAILED: got " + value);
            return false;
        }

        private void setUsbConfig(String config) {
            if (DEBUG) Slog.d(TAG, "setUsbConfig(" + config + ")");
            // set the new configuration
            // we always set it due to b/23631400, where adbd was getting killed
            // and not restarted due to property timeouts on some devices
            SystemProperties.set(USB_CONFIG_PROPERTY, config);
        }

        private void setAdbEnabled(boolean enable) {
            if (DEBUG) Slog.d(TAG, "setAdbEnabled: " + enable);
            if (enable != mAdbEnabled) {
                mAdbEnabled = enable;
                String oldFunctions = mCurrentFunctions;

                // Persist the adb setting
                String newFunction = applyAdbFunction(SystemProperties.get(
                        USB_PERSISTENT_CONFIG_PROPERTY, UsbManager.USB_FUNCTION_NONE));
                SystemProperties.set(USB_PERSISTENT_CONFIG_PROPERTY, newFunction);

                // Remove mtp from the config if file transfer is not enabled
                if (oldFunctions.equals(UsbManager.USB_FUNCTION_MTP) &&
                        !mUsbDataUnlocked && enable) {
                    oldFunctions = UsbManager.USB_FUNCTION_NONE;
                }

                setEnabledFunctions(oldFunctions, true, mUsbDataUnlocked);
                updateAdbNotification(false);
            }

            if (mDebuggingManager != null) {
                mDebuggingManager.setAdbEnabled(mAdbEnabled);
            }
        }

        /**
         * Evaluates USB function policies and applies the change accordingly.
         */
        private void setEnabledFunctions(String functions, boolean forceRestart,
                boolean usbDataUnlocked) {
            if (DEBUG) {
                Slog.d(TAG, "setEnabledFunctions functions=" + functions + ", "
                        + "forceRestart=" + forceRestart + ", usbDataUnlocked=" + usbDataUnlocked);
            }

            if (usbDataUnlocked != mUsbDataUnlocked) {
                mUsbDataUnlocked = usbDataUnlocked;
                updateUsbNotification(false);
                forceRestart = true;
            }

            // Try to set the enabled functions.
            final String oldFunctions = mCurrentFunctions;
            final boolean oldFunctionsApplied = mCurrentFunctionsApplied;
            if (trySetEnabledFunctions(functions, forceRestart)) {
                return;
            }

            // Didn't work.  Try to revert changes.
            // We always reapply the policy in case certain constraints changed such as
            // user restrictions independently of any other new functions we were
            // trying to activate.
            if (oldFunctionsApplied && !oldFunctions.equals(functions)) {
                Slog.e(TAG, "Failsafe 1: Restoring previous USB functions.");
                if (trySetEnabledFunctions(oldFunctions, false)) {
                    return;
                }
            }

            // Still didn't work.  Try to restore the default functions.
            Slog.e(TAG, "Failsafe 2: Restoring default USB functions.");
            if (trySetEnabledFunctions(null, false)) {
                return;
            }

            // Now we're desperate.  Ignore the default functions.
            // Try to get ADB working if enabled.
            Slog.e(TAG, "Failsafe 3: Restoring empty function list (with ADB if enabled).");
            if (trySetEnabledFunctions(UsbManager.USB_FUNCTION_NONE, false)) {
                return;
            }

            // Ouch.
            Slog.e(TAG, "Unable to set any USB functions!");
        }

        private boolean isNormalBoot() {
            String bootMode = SystemProperties.get(BOOT_MODE_PROPERTY, "unknown");
            return bootMode.equals(NORMAL_BOOT) || bootMode.equals("unknown");
        }

        private boolean trySetEnabledFunctions(String functions, boolean forceRestart) {
            if (functions == null || applyAdbFunction(functions)
                    .equals(UsbManager.USB_FUNCTION_NONE)) {
                functions = getDefaultFunctions();
            }
            functions = applyAdbFunction(functions);

            String oemFunctions = applyOemOverrideFunction(functions);

            if (!isNormalBoot() && !mCurrentFunctions.equals(functions)) {
                SystemProperties.set(getPersistProp(true), functions);
            }

            if ((!functions.equals(oemFunctions) &&
                    (mCurrentOemFunctions == null ||
                            !mCurrentOemFunctions.equals(oemFunctions)))
                    || !mCurrentFunctions.equals(functions)
                    || !mCurrentFunctionsApplied
                    || forceRestart) {
                Slog.i(TAG, "Setting USB config to " + functions);
                mCurrentFunctions = functions;
                mCurrentOemFunctions = oemFunctions;
                mCurrentFunctionsApplied = false;

                // Kick the USB stack to close existing connections.
                setUsbConfig(UsbManager.USB_FUNCTION_NONE);

                if (!waitForState(UsbManager.USB_FUNCTION_NONE)) {
                    Slog.e(TAG, "Failed to kick USB config");
                    return false;
                }

                // Set the new USB configuration.
                setUsbConfig(oemFunctions);

                if (mBootCompleted
                        && (UsbManager.containsFunction(functions, UsbManager.USB_FUNCTION_MTP)
                        || UsbManager.containsFunction(functions, UsbManager.USB_FUNCTION_PTP))) {
                    // Start up dependent services.
                    updateUsbStateBroadcastIfNeeded(true);
                }

                if (!waitForState(oemFunctions)) {
                    Slog.e(TAG, "Failed to switch USB config to " + functions);
                    return false;
                }

                mCurrentFunctionsApplied = true;
            }
            return true;
        }

        private String applyAdbFunction(String functions) {
            // Do not pass null pointer to the UsbManager.
            // There isnt a check there.
            if (functions == null) {
                functions = "";
            }
            if (mAdbEnabled) {
                functions = UsbManager.addFunction(functions, UsbManager.USB_FUNCTION_ADB);
            } else {
                functions = UsbManager.removeFunction(functions, UsbManager.USB_FUNCTION_ADB);
            }
            return functions;
        }

        private boolean isUsbTransferAllowed() {
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            return !userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER);
        }

        private void updateCurrentAccessory() {
            // We are entering accessory mode if we have received a request from the host
            // and the request has not timed out yet.
            boolean enteringAccessoryMode = hasMessages(MSG_ACCESSORY_MODE_ENTER_TIMEOUT);

            if (mConfigured && enteringAccessoryMode) {
                // successfully entered accessory mode
                if (mAccessoryStrings != null) {
                    mCurrentAccessory = new UsbAccessory(mAccessoryStrings);
                    Slog.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                    // defer accessoryAttached if system is not ready
                    if (mBootCompleted) {
                        getCurrentSettings().accessoryAttached(mCurrentAccessory);
                    } // else handle in boot completed
                } else {
                    Slog.e(TAG, "nativeGetAccessoryStrings failed");
                }
            } else {
                if (!enteringAccessoryMode) {
                    notifyAccessoryModeExit();
                } else if (DEBUG) {
                    Slog.v(TAG, "Debouncing accessory mode exit");
                }
            }
        }

        private void notifyAccessoryModeExit() {
            // make sure accessory mode is off
            // and restore default functions
            Slog.d(TAG, "exited USB accessory mode");
            setEnabledFunctions(null, false, false);

            if (mCurrentAccessory != null) {
                if (mBootCompleted) {
                    mSettingsManager.usbAccessoryRemoved(mCurrentAccessory);
                }
                mCurrentAccessory = null;
                mAccessoryStrings = null;
            }
        }

        private boolean isUsbStateChanged(Intent intent) {
            final Set<String> keySet = intent.getExtras().keySet();
            if (mBroadcastedIntent == null) {
                for (String key : keySet) {
                    if (intent.getBooleanExtra(key, false)) {
                        return true;
                    }
                }
            } else {
                if (!keySet.equals(mBroadcastedIntent.getExtras().keySet())) {
                    return true;
                }
                for (String key : keySet) {
                    if (intent.getBooleanExtra(key, false) !=
                            mBroadcastedIntent.getBooleanExtra(key, false)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void updateUsbStateBroadcastIfNeeded(boolean configChanged) {
            // send a sticky broadcast containing current USB state
            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(UsbManager.USB_CONNECTED, mConnected);
            intent.putExtra(UsbManager.USB_HOST_CONNECTED, mHostConnected);
            intent.putExtra(UsbManager.USB_CONFIGURED, mConfigured);
            intent.putExtra(UsbManager.USB_DATA_UNLOCKED,
                    isUsbTransferAllowed() && mUsbDataUnlocked);
            intent.putExtra(UsbManager.USB_CONFIG_CHANGED, configChanged);

            if (mCurrentFunctions != null) {
                String[] functions = mCurrentFunctions.split(",");
                for (int i = 0; i < functions.length; i++) {
                    final String function = functions[i];
                    if (UsbManager.USB_FUNCTION_NONE.equals(function)) {
                        continue;
                    }
                    intent.putExtra(function, true);
                }
            }

            // send broadcast intent only if the USB state has changed
            if (!isUsbStateChanged(intent) && !configChanged) {
                if (DEBUG) {
                    Slog.d(TAG, "skip broadcasting " + intent + " extras: " + intent.getExtras());
                }
                return;
            }

            if (DEBUG) Slog.d(TAG, "broadcasting " + intent + " extras: " + intent.getExtras());
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            mBroadcastedIntent = intent;
        }

        private void updateUsbFunctions() {
            updateAudioSourceFunction();
            updateMidiFunction();
        }

        private void updateAudioSourceFunction() {
            boolean enabled = UsbManager.containsFunction(mCurrentFunctions,
                    UsbManager.USB_FUNCTION_AUDIO_SOURCE);
            if (enabled != mAudioSourceEnabled) {
                int card = -1;
                int device = -1;

                if (enabled) {
                    Scanner scanner = null;
                    try {
                        scanner = new Scanner(new File(AUDIO_SOURCE_PCM_PATH));
                        card = scanner.nextInt();
                        device = scanner.nextInt();
                    } catch (FileNotFoundException e) {
                        Slog.e(TAG, "could not open audio source PCM file", e);
                    } finally {
                        if (scanner != null) {
                            scanner.close();
                        }
                    }
                }
                mUsbAlsaManager.setAccessoryAudioState(enabled, card, device);
                mAudioSourceEnabled = enabled;
            }
        }

        private void updateMidiFunction() {
            boolean enabled = UsbManager.containsFunction(mCurrentFunctions,
                    UsbManager.USB_FUNCTION_MIDI);
            if (enabled != mMidiEnabled) {
                if (enabled) {
                    Scanner scanner = null;
                    try {
                        scanner = new Scanner(new File(MIDI_ALSA_PATH));
                        mMidiCard = scanner.nextInt();
                        mMidiDevice = scanner.nextInt();
                    } catch (FileNotFoundException e) {
                        Slog.e(TAG, "could not open MIDI file", e);
                        enabled = false;
                    } finally {
                        if (scanner != null) {
                            scanner.close();
                        }
                    }
                }
                mMidiEnabled = enabled;
            }
            mUsbAlsaManager.setPeripheralMidiState(
                    mMidiEnabled && mConfigured, mMidiCard, mMidiDevice);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_STATE:
                    mConnected = (msg.arg1 == 1);
                    mConfigured = (msg.arg2 == 1);

                    updateUsbNotification(false);
                    updateAdbNotification(false);
                    if (mBootCompleted) {
                        updateUsbStateBroadcastIfNeeded(false);
                    }
                    if (UsbManager.containsFunction(mCurrentFunctions,
                            UsbManager.USB_FUNCTION_ACCESSORY)) {
                        updateCurrentAccessory();
                    }
                    if (mBootCompleted) {
                        if (!mConnected) {
                            // restore defaults when USB is disconnected
                            setEnabledFunctions(null, !mAdbEnabled, false);
                        }
                        updateUsbFunctions();
                    } else {
                        mPendingBootBroadcast = true;
                    }
                    break;
                case MSG_UPDATE_PORT_STATE:
                    SomeArgs args = (SomeArgs) msg.obj;
                    boolean prevHostConnected = mHostConnected;
                    UsbPort port = (UsbPort) args.arg1;
                    UsbPortStatus status = (UsbPortStatus) args.arg2;
                    mHostConnected = status.getCurrentDataRole() == UsbPort.DATA_ROLE_HOST;
                    mSourcePower = status.getCurrentPowerRole() == UsbPort.POWER_ROLE_SOURCE;
                    mSinkPower = status.getCurrentPowerRole() == UsbPort.POWER_ROLE_SINK;
                    mAudioAccessoryConnected =
                            (status.getCurrentMode() == UsbPort.MODE_AUDIO_ACCESSORY);
                    mAudioAccessorySupported = port.isModeSupported(UsbPort.MODE_AUDIO_ACCESSORY);
                    // Ideally we want to see if PR_SWAP and DR_SWAP is supported.
                    // But, this should be suffice, since, all four combinations are only supported
                    // when PR_SWAP and DR_SWAP are supported.
                    mSupportsAllCombinations = status.isRoleCombinationSupported(
                            UsbPort.POWER_ROLE_SOURCE, UsbPort.DATA_ROLE_HOST)
                            && status.isRoleCombinationSupported(UsbPort.POWER_ROLE_SINK,
                            UsbPort.DATA_ROLE_HOST)
                            && status.isRoleCombinationSupported(UsbPort.POWER_ROLE_SOURCE,
                            UsbPort.DATA_ROLE_DEVICE)
                            && status.isRoleCombinationSupported(UsbPort.POWER_ROLE_SINK,
                            UsbPort.DATA_ROLE_HOST);

                    args.recycle();
                    updateUsbNotification(false);
                    if (mBootCompleted) {
                        if (mHostConnected || prevHostConnected) {
                            updateUsbStateBroadcastIfNeeded(false);
                        }
                    } else {
                        mPendingBootBroadcast = true;
                    }
                    break;
                case MSG_UPDATE_CHARGING_STATE:
                    mUsbCharging = (msg.arg1 == 1);
                    updateUsbNotification(false);
                    break;
                case MSG_UPDATE_HOST_STATE:
                    Iterator devices = (Iterator) msg.obj;
                    boolean connected = (msg.arg1 == 1);

                    if (DEBUG) {
                        Slog.i(TAG, "HOST_STATE connected:" + connected);
                    }

                    mHideUsbNotification = false;
                    while (devices.hasNext()) {
                        Map.Entry pair = (Map.Entry) devices.next();
                        if (DEBUG) {
                            Slog.i(TAG, pair.getKey() + " = " + pair.getValue());
                        }
                        UsbDevice device = (UsbDevice) pair.getValue();
                        int configurationCount = device.getConfigurationCount() - 1;
                        while (configurationCount >= 0) {
                            UsbConfiguration config = device.getConfiguration(configurationCount);
                            configurationCount--;
                            int interfaceCount = config.getInterfaceCount() - 1;
                            while (interfaceCount >= 0) {
                                UsbInterface intrface = config.getInterface(interfaceCount);
                                interfaceCount--;
                                if (sBlackListedInterfaces.contains(intrface.getInterfaceClass())) {
                                    mHideUsbNotification = true;
                                    break;
                                }
                            }
                        }
                    }
                    updateUsbNotification(false);
                    break;
                case MSG_ENABLE_ADB:
                    setAdbEnabled(msg.arg1 == 1);
                    break;
                case MSG_SET_CURRENT_FUNCTIONS:
                    String functions = (String) msg.obj;
                    setEnabledFunctions(functions, false, msg.arg1 == 1);
                    break;
                case MSG_UPDATE_USER_RESTRICTIONS:
                    // Restart the USB stack if USB transfer is enabled but no longer allowed.
                    final boolean forceRestart = mUsbDataUnlocked
                            && isUsbDataTransferActive()
                            && !isUsbTransferAllowed();
                    setEnabledFunctions(
                            mCurrentFunctions, forceRestart, mUsbDataUnlocked && !forceRestart);
                    break;
                case MSG_SYSTEM_READY:
                    updateUsbNotification(false);
                    updateAdbNotification(false);
                    updateUsbFunctions();
                    break;
                case MSG_LOCALE_CHANGED:
                    updateAdbNotification(true);
                    updateUsbNotification(true);
                    break;
                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    if (mPendingBootBroadcast) {
                        updateUsbStateBroadcastIfNeeded(false);
                        mPendingBootBroadcast = false;
                    }
                    setEnabledFunctions(null, false, false);
                    if (mCurrentAccessory != null) {
                        getCurrentSettings().accessoryAttached(mCurrentAccessory);
                    }
                    if (mDebuggingManager != null) {
                        mDebuggingManager.setAdbEnabled(mAdbEnabled);
                    }
                    break;
                case MSG_USER_SWITCHED: {
                    if (mCurrentUser != msg.arg1) {
                        // Restart the USB stack and re-apply user restrictions for MTP or PTP.
                        if (mUsbDataUnlocked
                                && isUsbDataTransferActive()
                                && mCurrentUser != UserHandle.USER_NULL) {
                            Slog.v(TAG, "Current user switched to " + msg.arg1
                                    + "; resetting USB host stack for MTP or PTP");
                            // avoid leaking sensitive data from previous user
                            setEnabledFunctions(null, true, false);
                        }
                        mCurrentUser = msg.arg1;
                    }
                    break;
                }
                case MSG_ACCESSORY_MODE_ENTER_TIMEOUT: {
                    if (DEBUG) {
                        Slog.v(TAG, "Accessory mode enter timeout: " + mConnected);
                    }
                    if (!mConnected || !UsbManager.containsFunction(
                            mCurrentFunctions,
                            UsbManager.USB_FUNCTION_ACCESSORY)) {
                        notifyAccessoryModeExit();
                    }
                    break;
                }
            }
        }

        private boolean isUsbDataTransferActive() {
            return UsbManager.containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MTP)
                    || UsbManager.containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_PTP);
        }

        public UsbAccessory getCurrentAccessory() {
            return mCurrentAccessory;
        }

        private void updateUsbNotification(boolean force) {
            if (mNotificationManager == null || !mUseUsbNotification
                    || ("0".equals(SystemProperties.get("persist.charging.notify")))) {
                return;
            }

            // Dont show the notification when connected to a USB peripheral
            // and the link does not support PR_SWAP and DR_SWAP
            if (mHideUsbNotification && !mSupportsAllCombinations) {
                if (mUsbNotificationId != 0) {
                    mNotificationManager.cancelAsUser(null, mUsbNotificationId,
                            UserHandle.ALL);
                    mUsbNotificationId = 0;
                    Slog.d(TAG, "Clear notification");
                }
                return;
            }

            int id = 0;
            int titleRes = 0;
            Resources r = mContext.getResources();
            if (mAudioAccessoryConnected && !mAudioAccessorySupported) {
                titleRes = com.android.internal.R.string.usb_unsupported_audio_accessory_title;
                id = SystemMessage.NOTE_USB_AUDIO_ACCESSORY_NOT_SUPPORTED;
            } else if (mConnected) {
                if (!mUsbDataUnlocked) {
                    if (mSourcePower) {
                        titleRes = com.android.internal.R.string.usb_supplying_notification_title;
                        id = SystemMessage.NOTE_USB_SUPPLYING;
                    } else {
                        titleRes = com.android.internal.R.string.usb_charging_notification_title;
                        id = SystemMessage.NOTE_USB_CHARGING;
                    }
                } else if (UsbManager.containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_MTP)) {
                    titleRes = com.android.internal.R.string.usb_mtp_notification_title;
                    id = SystemMessage.NOTE_USB_MTP;
                } else if (UsbManager.containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_PTP)) {
                    titleRes = com.android.internal.R.string.usb_ptp_notification_title;
                    id = SystemMessage.NOTE_USB_PTP;
                } else if (UsbManager.containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_MIDI)) {
                    titleRes = com.android.internal.R.string.usb_midi_notification_title;
                    id = SystemMessage.NOTE_USB_MIDI;
                } else if (UsbManager.containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_ACCESSORY)) {
                    titleRes = com.android.internal.R.string.usb_accessory_notification_title;
                    id = SystemMessage.NOTE_USB_ACCESSORY;
                } else if (mSourcePower) {
                    titleRes = com.android.internal.R.string.usb_supplying_notification_title;
                    id = SystemMessage.NOTE_USB_SUPPLYING;
                } else {
                    titleRes = com.android.internal.R.string.usb_charging_notification_title;
                    id = SystemMessage.NOTE_USB_CHARGING;
                }
            } else if (mSourcePower) {
                titleRes = com.android.internal.R.string.usb_supplying_notification_title;
                id = SystemMessage.NOTE_USB_SUPPLYING;
            } else if (mHostConnected && mSinkPower && mUsbCharging) {
                titleRes = com.android.internal.R.string.usb_charging_notification_title;
                id = SystemMessage.NOTE_USB_CHARGING;
            }
            if (id != mUsbNotificationId || force) {
                // clear notification if title needs changing
                if (mUsbNotificationId != 0) {
                    mNotificationManager.cancelAsUser(null, mUsbNotificationId,
                            UserHandle.ALL);
                    Slog.d(TAG, "Clear notification");
                    mUsbNotificationId = 0;
                }
                if (id != 0) {
                    CharSequence message;
                    CharSequence title = r.getText(titleRes);
                    PendingIntent pi;
                    String channel;

                    if (titleRes
                            != com.android.internal.R.string
                            .usb_unsupported_audio_accessory_title) {
                        Intent intent = Intent.makeRestartActivityTask(
                                new ComponentName("com.android.settings",
                                        "com.android.settings.deviceinfo.UsbModeChooserActivity"));
                        pi = PendingIntent.getActivityAsUser(mContext, 0,
                                intent, 0, null, UserHandle.CURRENT);
                        channel = SystemNotificationChannels.USB;
                        message = r.getText(
                                com.android.internal.R.string.usb_notification_message);
                    } else {
                        final Intent intent = new Intent();
                        intent.setClassName("com.android.settings",
                                "com.android.settings.HelpTrampoline");
                        intent.putExtra(Intent.EXTRA_TEXT,
                                "help_url_audio_accessory_not_supported");

                        if (mContext.getPackageManager().resolveActivity(intent, 0) != null) {
                            pi = PendingIntent.getActivity(mContext, 0, intent, 0);
                        } else {
                            pi = null;
                        }

                        channel = SystemNotificationChannels.ALERTS;
                        message = r.getText(
                                com.android.internal.R.string
                                        .usb_unsupported_audio_accessory_message);
                    }

                    Notification.Builder builder = new Notification.Builder(mContext, channel)
                                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                                    .setWhen(0)
                                    .setOngoing(true)
                                    .setTicker(title)
                                    .setDefaults(0)  // please be quiet
                                    .setColor(mContext.getColor(
                                            com.android.internal.R.color
                                                    .system_notification_accent_color))
                                    .setContentTitle(title)
                                    .setContentText(message)
                                    .setContentIntent(pi)
                                    .setVisibility(Notification.VISIBILITY_PUBLIC);

                    if (titleRes
                            == com.android.internal.R.string
                            .usb_unsupported_audio_accessory_title) {
                        builder.setStyle(new Notification.BigTextStyle()
                                .bigText(message));
                    }
                    Notification notification = builder.build();

                    mNotificationManager.notifyAsUser(null, id, notification,
                            UserHandle.ALL);
                    Slog.d(TAG, "push notification:" + title);
                    mUsbNotificationId = id;
                }
            }
        }

        private void updateAdbNotification(boolean force) {
            if (mNotificationManager == null) return;
            final int id = SystemMessage.NOTE_ADB_ACTIVE;
            final int titleRes = com.android.internal.R.string.adb_active_notification_title;

            if (mAdbEnabled && mConnected) {
                if ("0".equals(SystemProperties.get("persist.adb.notify"))) return;

                if (force && mAdbNotificationShown) {
                    mAdbNotificationShown = false;
                    mNotificationManager.cancelAsUser(null, id, UserHandle.ALL);
                }

                if (!mAdbNotificationShown) {
                    Resources r = mContext.getResources();
                    CharSequence title = r.getText(titleRes);
                    CharSequence message = r.getText(
                            com.android.internal.R.string.adb_active_notification_message);

                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                            intent, 0, null, UserHandle.CURRENT);

                    Notification notification =
                            new Notification.Builder(mContext, SystemNotificationChannels.DEVELOPER)
                                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                                    .setWhen(0)
                                    .setOngoing(true)
                                    .setTicker(title)
                                    .setDefaults(0)  // please be quiet
                                    .setColor(mContext.getColor(
                                            com.android.internal.R.color
                                                    .system_notification_accent_color))
                                    .setContentTitle(title)
                                    .setContentText(message)
                                    .setContentIntent(pi)
                                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                                    .extend(new Notification.TvExtender()
                                            .setChannelId(ADB_NOTIFICATION_CHANNEL_ID_TV))
                                    .build();
                    mAdbNotificationShown = true;
                    mNotificationManager.notifyAsUser(null, id, notification,
                            UserHandle.ALL);
                }
            } else if (mAdbNotificationShown) {
                mAdbNotificationShown = false;
                mNotificationManager.cancelAsUser(null, id, UserHandle.ALL);
            }
        }

        private String getDefaultFunctions() {
            String func = SystemProperties.get(getPersistProp(true),
                    UsbManager.USB_FUNCTION_NONE);
            // if ADB is enabled, reset functions to ADB
            // else enable MTP as usual.
            if (UsbManager.containsFunction(func, UsbManager.USB_FUNCTION_ADB)) {
                return UsbManager.USB_FUNCTION_ADB;
            } else {
                return UsbManager.USB_FUNCTION_MTP;
            }
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("USB Device State:");
            pw.println("  mCurrentFunctions: " + mCurrentFunctions);
            pw.println("  mCurrentOemFunctions: " + mCurrentOemFunctions);
            pw.println("  mCurrentFunctionsApplied: " + mCurrentFunctionsApplied);
            pw.println("  mConnected: " + mConnected);
            pw.println("  mConfigured: " + mConfigured);
            pw.println("  mUsbDataUnlocked: " + mUsbDataUnlocked);
            pw.println("  mCurrentAccessory: " + mCurrentAccessory);
            pw.println("  mHostConnected: " + mHostConnected);
            pw.println("  mSourcePower: " + mSourcePower);
            pw.println("  mSinkPower: " + mSinkPower);
            pw.println("  mUsbCharging: " + mUsbCharging);
            pw.println("  mHideUsbNotification: " + mHideUsbNotification);
            pw.println("  mAudioAccessoryConnected: " + mAudioAccessoryConnected);

            try {
                pw.println("  Kernel state: "
                        + FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim());
                pw.println("  Kernel function list: "
                        + FileUtils.readTextFile(new File(FUNCTIONS_PATH), 0, null).trim());
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
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory,
            UsbUserSettingsManager settings) {
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
        settings.checkPermission(accessory);
        return nativeOpenAccessory();
    }

    public boolean isFunctionEnabled(String function) {
        return UsbManager.containsFunction(SystemProperties.get(USB_CONFIG_PROPERTY), function);
    }

    public void setCurrentFunctions(String functions, boolean usbDataUnlocked) {
        if (DEBUG) {
            Slog.d(TAG, "setCurrentFunctions(" + functions + ", " +
                    usbDataUnlocked + ")");
        }
        mHandler.sendMessage(MSG_SET_CURRENT_FUNCTIONS, functions, usbDataUnlocked);
    }

    private void readOemUsbOverrideConfig() {
        String[] configList = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_oemUsbModeOverride);

        if (configList != null) {
            for (String config : configList) {
                String[] items = config.split(":");
                if (items.length == 3 || items.length == 4) {
                    if (mOemModeMap == null) {
                        mOemModeMap = new HashMap<>();
                    }
                    HashMap<String, Pair<String, String>> overrideMap
                            = mOemModeMap.get(items[0]);
                    if (overrideMap == null) {
                        overrideMap = new HashMap<>();
                        mOemModeMap.put(items[0], overrideMap);
                    }

                    // Favoring the first combination if duplicate exists
                    if (!overrideMap.containsKey(items[1])) {
                        if (items.length == 3) {
                            overrideMap.put(items[1], new Pair<>(items[2], ""));
                        } else {
                            overrideMap.put(items[1], new Pair<>(items[2], items[3]));
                        }
                    }
                }
            }
        }
    }

    private String applyOemOverrideFunction(String usbFunctions) {
        if ((usbFunctions == null) || (mOemModeMap == null)) {
            return usbFunctions;
        }

        String bootMode = SystemProperties.get(BOOT_MODE_PROPERTY, "unknown");
        Slog.d(TAG, "applyOemOverride usbfunctions=" + usbFunctions + " bootmode=" + bootMode);

        Map<String, Pair<String, String>> overridesMap =
                mOemModeMap.get(bootMode);
        // Check to ensure that the oem is not overriding in the normal
        // boot mode
        if (overridesMap != null && !(bootMode.equals(NORMAL_BOOT) ||
                bootMode.equals("unknown"))) {
            Pair<String, String> overrideFunctions =
                    overridesMap.get(usbFunctions);
            if (overrideFunctions != null) {
                Slog.d(TAG, "OEM USB override: " + usbFunctions
                        + " ==> " + overrideFunctions.first
                        + " persist across reboot "
                        + overrideFunctions.second);
                if (!overrideFunctions.second.equals("")) {
                    String newFunction;
                    if (mAdbEnabled) {
                        newFunction = UsbManager.addFunction(overrideFunctions.second,
                                UsbManager.USB_FUNCTION_ADB);
                    } else {
                        newFunction = overrideFunctions.second;
                    }
                    Slog.d(TAG, "OEM USB override persisting: " + newFunction + "in prop: "
                            + UsbDeviceManager.getPersistProp(false));
                    SystemProperties.set(UsbDeviceManager.getPersistProp(false),
                            newFunction);
                }
                return overrideFunctions.first;
            } else if (mAdbEnabled) {
                String newFunction = UsbManager.addFunction(UsbManager.USB_FUNCTION_NONE,
                        UsbManager.USB_FUNCTION_ADB);
                SystemProperties.set(UsbDeviceManager.getPersistProp(false),
                        newFunction);
            } else {
                SystemProperties.set(UsbDeviceManager.getPersistProp(false),
                        UsbManager.USB_FUNCTION_NONE);
            }
        }
        // return passed in functions as is.
        return usbFunctions;
    }

    public static String getPersistProp(boolean functions) {
        String bootMode = SystemProperties.get(BOOT_MODE_PROPERTY, "unknown");
        String persistProp = USB_PERSISTENT_CONFIG_PROPERTY;
        if (!(bootMode.equals(NORMAL_BOOT) || bootMode.equals("unknown"))) {
            if (functions) {
                persistProp = "persist.sys.usb." + bootMode + ".func";
            } else {
                persistProp = "persist.sys.usb." + bootMode + ".config";
            }
        }

        return persistProp;
    }


    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        if (mDebuggingManager != null) {
            mDebuggingManager.allowUsbDebugging(alwaysAllow, publicKey);
        }
    }

    public void denyUsbDebugging() {
        if (mDebuggingManager != null) {
            mDebuggingManager.denyUsbDebugging();
        }
    }

    public void clearUsbDebuggingKeys() {
        if (mDebuggingManager != null) {
            mDebuggingManager.clearUsbDebuggingKeys();
        } else {
            throw new RuntimeException("Cannot clear Usb Debugging keys, "
                    + "UsbDebuggingManager not enabled");
        }
    }

    public void dump(IndentingPrintWriter pw) {
        if (mHandler != null) {
            mHandler.dump(pw);
        }
        if (mDebuggingManager != null) {
            mDebuggingManager.dump(pw);
        }
    }

    private native String[] nativeGetAccessoryStrings();

    private native ParcelFileDescriptor nativeOpenAccessory();

    private native boolean nativeIsStartRequested();

    private native int nativeGetAudioMode();
}
