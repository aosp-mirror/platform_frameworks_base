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
import android.hardware.usb.UsbManager;
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
import com.android.server.FgThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

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
    private static final int MSG_SET_USB_DATA_UNLOCKED = 6;

    private static final int AUDIO_MODE_SOURCE = 1;

    // Delay for debouncing USB disconnects.
    // We often get rapid connect/disconnect events when enabling USB functions,
    // which need debouncing.
    private static final int UPDATE_DELAY = 1000;

    // Time we received a request to enter USB accessory mode
    private long mAccessoryModeRequestTime = 0;

    // Timeout for entering USB request mode.
    // Request is cancelled if host does not configure device within 10 seconds.
    private static final int ACCESSORY_REQUEST_TIMEOUT = 10 * 1000;

    private UsbHandler mHandler;
    private boolean mBootCompleted;

    private final Object mLock = new Object();

    private final Context mContext;
    private final ContentResolver mContentResolver;
    @GuardedBy("mLock")
    private UsbSettingsManager mCurrentSettings;
    private NotificationManager mNotificationManager;
    private final boolean mHasUsbAccessory;
    private boolean mUseUsbNotification;
    private boolean mAdbEnabled;
    private boolean mAudioSourceEnabled;
    private boolean mMidiEnabled;
    private int mMidiCard;
    private int mMidiDevice;
    private Map<String, List<Pair<String, String>>> mOemModeMap;
    private String[] mAccessoryStrings;
    private UsbDebuggingManager mDebuggingManager;
    private final UsbAlsaManager mUsbAlsaManager;

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

    public UsbDeviceManager(Context context, UsbAlsaManager alsaManager) {
        mContext = context;
        mUsbAlsaManager = alsaManager;
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
    }

    public void setCurrentSettings(UsbSettingsManager settings) {
        synchronized (mLock) {
            mCurrentSettings = settings;
        }
    }

    private UsbSettingsManager getCurrentSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
        }
    }

    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        // We do not show the USB notification if the primary volume supports mass storage.
        // The legacy mass storage UI will be used instead.
        boolean massStorageSupported = false;
        final StorageManager storageManager = StorageManager.from(mContext);
        final StorageVolume primary = storageManager.getPrimaryVolume();
        massStorageSupported = primary != null && primary.allowMassStorage();
        mUseUsbNotification = !massStorageSupported;

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
            mAccessoryModeRequestTime = SystemClock.elapsedRealtime();
            setCurrentFunctions(functions);
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
            address[i % (ETH_ALEN - 1) + 1] ^= (int)serial.charAt(i);
        }
        String addrString = String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
            address[0], address[1], address[2], address[3], address[4], address[5]);
        try {
            FileUtils.stringToFile(RNDIS_ETH_ADDR_PATH, addrString);
        } catch (IOException e) {
           Slog.e(TAG, "failed to write to " + RNDIS_ETH_ADDR_PATH);
        }
    }

     private static String addFunction(String functions, String function) {
         if ("none".equals(functions)) {
             return function;
         }
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
        if (split.length == 1 && split[0] == null) {
            return "none";
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
        return Arrays.asList(functions.split(",")).contains(function);
    }

    private final class UsbHandler extends Handler {

        // current USB state
        private boolean mConnected;
        private boolean mConfigured;
        private boolean mUsbDataUnlocked;
        private String mCurrentFunctions;
        private UsbAccessory mCurrentAccessory;
        private int mUsbNotificationId;
        private String mDefaultFunctions;
        private boolean mAdbNotificationShown;
        private int mCurrentUser = UserHandle.USER_NULL;

        private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DEBUG) Slog.d(TAG, "boot completed");
                mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
            }
        };

        private final BroadcastReceiver mUserSwitchedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                mHandler.obtainMessage(MSG_USER_SWITCHED, userId, 0).sendToTarget();
            }
        };

        public UsbHandler(Looper looper) {
            super(looper);
            try {
                // TODO: rename persist.sys.usb.config to something more descriptive.
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

                mAdbEnabled = containsFunction(
                        SystemProperties.get(UsbManager.ADB_PERSISTENT_PROPERTY, "adb"),
                        UsbManager.USB_FUNCTION_ADB);

                mCurrentFunctions = getDefaultFunctions();
                String state = FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim();
                updateState(state);

                // register observer to listen for settings changes
                mContentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                                false, new AdbSettingsObserver());

                // Watch for USB configuration changes
                mUEventObserver.startObserving(USB_STATE_MATCH);
                mUEventObserver.startObserving(ACCESSORY_START_MATCH);

                IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
                filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                mContext.registerReceiver(mBootCompletedReceiver, filter);
                mContext.registerReceiver(
                        mUserSwitchedReceiver, new IntentFilter(Intent.ACTION_USER_SWITCHED));
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

        private void updatePersistentProperty() {
            String newValue = getDefaultFunctions();
            String value = SystemProperties.get(UsbManager.ADB_PERSISTENT_PROPERTY);
            if (DEBUG) { Slog.d(TAG, "updatePersistentProperty newValue=" + newValue + " value=" + value); }
            if (!newValue.equals(value)) {
                SystemProperties.set(UsbManager.ADB_PERSISTENT_PROPERTY, getDefaultFunctions());
            }
            waitForState(newValue);
        }

        private boolean waitForState(String state) {
            // wait for the transition to complete.
            // give up after 1 second.
            for (int i = 0; i < 20; i++) {
                // State transition is done when sys.usb.state is set to the new configuration
                if (state.equals(SystemProperties.get("sys.usb.state"))) return true;
                SystemClock.sleep(50);
            }
            Slog.e(TAG, "waitForState(" + state + ") FAILED");
            return false;
        }

        private boolean setUsbConfig(String config) {
            if (DEBUG) Slog.d(TAG, "setUsbConfig(" + config + ")");
            // set the new configuration
            String oldConfig = SystemProperties.get(UsbManager.USB_SETTINGS_PROPERTY);
            if (!config.equals(oldConfig)) {
                SystemProperties.set(UsbManager.USB_SETTINGS_PROPERTY, config);
            }
            return waitForState(config);
        }

        private void setAdbEnabled(boolean enable) {
            if (DEBUG) Slog.d(TAG, "setAdbEnabled: " + enable);
            if (enable != mAdbEnabled) {
                mAdbEnabled = enable;
                // Due to the persist.sys.usb.config property trigger, changing adb state requires
                // persisting default function
                updatePersistentProperty();
                // After persisting them use the lock-down aware function set
                setEnabledFunctions(getDefaultFunctions());
                updateAdbNotification();
            }
            if (mDebuggingManager != null) {
                mDebuggingManager.setAdbEnabled(mAdbEnabled);
            }
        }

        /**
         * Stop and start the USB driver. This is needed to close all outstanding
         * USB connections.
         */
        private void restartCurrentFunction() {
            setUsbConfig("none");
            setUsbConfig(mCurrentFunctions);
        }

        private void setEnabledFunctions(String functions) {
            if (DEBUG) Slog.d(TAG, "setEnabledFunctions " + functions);

            if (functions == null) {
                functions = getDefaultFunctions();
            }

            if (mAdbEnabled) {
                functions = addFunction(functions, UsbManager.USB_FUNCTION_ADB);
            } else {
                functions = removeFunction(functions, UsbManager.USB_FUNCTION_ADB);
            }
            if (!mCurrentFunctions.equals(functions)) {
                if (!setUsbConfig("none")) {
                    Slog.e(TAG, "Failed to disable USB");
                    // revert to previous configuration if we fail
                    setUsbConfig(mCurrentFunctions);
                    return;
                }
                if (setUsbConfig(functions)) {
                    mCurrentFunctions = functions;
                } else {
                    Slog.e(TAG, "Failed to switch USB config to " + functions);
                    // revert to previous configuration if we fail
                    setUsbConfig(mCurrentFunctions);
                }
            }
        }

        private void updateCurrentAccessory() {
            // We are entering accessory mode if we have received a request from the host
            // and the request has not timed out yet.
            boolean enteringAccessoryMode =
                    mAccessoryModeRequestTime > 0 &&
                        SystemClock.elapsedRealtime() <
                            mAccessoryModeRequestTime + ACCESSORY_REQUEST_TIMEOUT;

            if (mConfigured && enteringAccessoryMode) {
                // successfully entered accessory mode

                if (mAccessoryStrings != null) {
                    mCurrentAccessory = new UsbAccessory(mAccessoryStrings);
                    Slog.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                    // defer accessoryAttached if system is not ready
                    if (mBootCompleted) {
                        getCurrentSettings().accessoryAttached(mCurrentAccessory);
                    } // else handle in mBootCompletedReceiver
                } else {
                    Slog.e(TAG, "nativeGetAccessoryStrings failed");
                }
            } else if (!enteringAccessoryMode) {
                // make sure accessory mode is off
                // and restore default functions
                Slog.d(TAG, "exited USB accessory mode");
                setEnabledFunctions(getDefaultFunctions());

                if (mCurrentAccessory != null) {
                    if (mBootCompleted) {
                        getCurrentSettings().accessoryDetached(mCurrentAccessory);
                    }
                    mCurrentAccessory = null;
                    mAccessoryStrings = null;
                }
            }
        }

        private void updateUsbState() {
            // send a sticky broadcast containing current USB state
            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(UsbManager.USB_CONNECTED, mConnected);
            intent.putExtra(UsbManager.USB_CONFIGURED, mConfigured);
            intent.putExtra(UsbManager.USB_DATA_UNLOCKED, mUsbDataUnlocked);

            if (mCurrentFunctions != null) {
                String[] functions = mCurrentFunctions.split(",");
                for (int i = 0; i < functions.length; i++) {
                    intent.putExtra(functions[i], true);
                }
            }

            if (DEBUG) Slog.d(TAG, "broadcasting " + intent + " connected: " + mConnected
                                    + " configured: " + mConfigured);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void updateAudioSourceFunction() {
            boolean enabled = containsFunction(mCurrentFunctions,
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
            boolean enabled = containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MIDI);
            if (enabled != mMidiEnabled) {
                if (enabled) {
                    Scanner scanner = null;
                    try {
                        scanner = new Scanner(new File(MIDI_ALSA_PATH));
                        mMidiCard = scanner.nextInt();
                        mMidiDevice = scanner.nextInt();
                    } catch (FileNotFoundException e) {
                        Slog.e(TAG, "could not open MIDI PCM file", e);
                        enabled = false;
                    } finally {
                        if (scanner != null) {
                            scanner.close();
                        }
                    }
                }
                mMidiEnabled = enabled;
            }
            mUsbAlsaManager.setPeripheralMidiState(mMidiEnabled && mConfigured, mMidiCard, mMidiDevice);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_STATE:
                    mConnected = (msg.arg1 == 1);
                    mConfigured = (msg.arg2 == 1);
                    if (!mConnected) {
                        // When a disconnect occurs, relock access to sensitive user data
                        mUsbDataUnlocked = false;
                    }
                    updateUsbNotification();
                    updateAdbNotification();
                    if (containsFunction(mCurrentFunctions,
                            UsbManager.USB_FUNCTION_ACCESSORY)) {
                        updateCurrentAccessory();
                    } else if (!mConnected) {
                        // restore defaults when USB is disconnected
                        setEnabledFunctions(getDefaultFunctions());
                    }
                    if (mBootCompleted) {
                        updateUsbState();
                        updateAudioSourceFunction();
                        updateMidiFunction();
                    }
                    break;
                case MSG_ENABLE_ADB:
                    setAdbEnabled(msg.arg1 == 1);
                    break;
                case MSG_SET_CURRENT_FUNCTIONS:
                    String functions = (String)msg.obj;
                    setEnabledFunctions(functions);
                    break;
                case MSG_SET_USB_DATA_UNLOCKED:
                    mUsbDataUnlocked = (msg.arg1 == 1);
                    updateUsbNotification();
                    updateUsbState();
                    restartCurrentFunction();
                    break;
                case MSG_SYSTEM_READY:
                    setUsbConfig(mCurrentFunctions);
                    updatePersistentProperty();
                    updateUsbNotification();
                    updateAdbNotification();
                    updateUsbState();
                    updateAudioSourceFunction();
                    updateMidiFunction();
                    break;
                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    setUsbConfig(mCurrentFunctions);
                    if (mCurrentAccessory != null) {
                        getCurrentSettings().accessoryAttached(mCurrentAccessory);
                    }
                    if (mDebuggingManager != null) {
                        mDebuggingManager.setAdbEnabled(mAdbEnabled);
                    }
                    break;
                case MSG_USER_SWITCHED: {
                    UserManager userManager =
                            (UserManager) mContext.getSystemService(Context.USER_SERVICE);
                    UserHandle userHandle = new UserHandle(msg.arg1);
                    if (userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER,
                            userHandle)) {
                        Slog.v(TAG, "Switched to user " + msg.arg1 +
                                " with DISALLOW_USB_FILE_TRANSFER restriction; disabling USB.");
                        setUsbConfig("none");
                        mCurrentUser = msg.arg1;
                        break;
                    }

                    final boolean mtpActive =
                            containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MTP)
                            || containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_PTP);
                    if (mtpActive && mCurrentUser != UserHandle.USER_NULL) {
                        Slog.v(TAG, "Current user switched; resetting USB host stack for MTP");
                        setUsbConfig("none");
                        setUsbConfig(mCurrentFunctions);
                    }
                    mCurrentUser = msg.arg1;
                    break;
                }
            }
        }

        public UsbAccessory getCurrentAccessory() {
            return mCurrentAccessory;
        }

        private void updateUsbNotification() {
            if (mNotificationManager == null || !mUseUsbNotification) return;
            int id = 0;
            Resources r = mContext.getResources();
            if (mConnected) {
                if (!mUsbDataUnlocked) {
                    id = com.android.internal.R.string.usb_charging_notification_title;
                } else if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MTP)) {
                    id = com.android.internal.R.string.usb_mtp_notification_title;
                } else if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_PTP)) {
                    id = com.android.internal.R.string.usb_ptp_notification_title;
                } else if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_MIDI)) {
                    id = com.android.internal.R.string.usb_midi_notification_title;
                } else if (containsFunction(mCurrentFunctions,
                        UsbManager.USB_FUNCTION_MASS_STORAGE)) {
                    id = com.android.internal.R.string.usb_cd_installer_notification_title;
                } else if (containsFunction(mCurrentFunctions, UsbManager.USB_FUNCTION_ACCESSORY)) {
                    id = com.android.internal.R.string.usb_accessory_notification_title;
                } else {
                    id = com.android.internal.R.string.usb_charging_notification_title;
                }
            }
            if (id != mUsbNotificationId) {
                // clear notification if title needs changing
                if (mUsbNotificationId != 0) {
                    mNotificationManager.cancelAsUser(null, mUsbNotificationId,
                            UserHandle.ALL);
                    mUsbNotificationId = 0;
                }
                if (id != 0) {
                    CharSequence message = r.getText(
                            com.android.internal.R.string.usb_notification_message);
                    CharSequence title = r.getText(id);

                    Intent intent = Intent.makeRestartActivityTask(
                            new ComponentName("com.android.settings",
                                    "com.android.settings.deviceinfo.UsbModeChooserActivity"));
                    PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                            intent, 0, null, UserHandle.CURRENT);

                    Notification notification = new Notification.Builder(mContext)
                            .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                            .setWhen(0)
                            .setOngoing(true)
                            .setTicker(title)
                            .setDefaults(0)  // please be quiet
                            .setPriority(Notification.PRIORITY_MIN)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(message)
                            .setContentIntent(pi)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .build();
                    mNotificationManager.notifyAsUser(null, id, notification,
                            UserHandle.ALL);
                    mUsbNotificationId = id;
                }
            }
        }

        private void updateAdbNotification() {
            if (mNotificationManager == null) return;
            final int id = com.android.internal.R.string.adb_active_notification_title;
            if (mAdbEnabled && mConnected) {
                if ("0".equals(SystemProperties.get("persist.adb.notify"))) return;

                if (!mAdbNotificationShown) {
                    Resources r = mContext.getResources();
                    CharSequence title = r.getText(id);
                    CharSequence message = r.getText(
                            com.android.internal.R.string.adb_active_notification_message);

                    Intent intent = Intent.makeRestartActivityTask(
                            new ComponentName("com.android.settings",
                                    "com.android.settings.DevelopmentSettings"));
                    PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                            intent, 0, null, UserHandle.CURRENT);

                    Notification notification = new Notification.Builder(mContext)
                            .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                            .setWhen(0)
                            .setOngoing(true)
                            .setTicker(title)
                            .setDefaults(0)  // please be quiet
                            .setPriority(Notification.PRIORITY_LOW)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(message)
                            .setContentIntent(pi)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
            UserManager userManager = (UserManager)mContext.getSystemService(Context.USER_SERVICE);
            if(userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER,
                new UserHandle(mCurrentUser))) {
               return "none";
             }
             return mDefaultFunctions;
        }

        public void dump(FileDescriptor fd, PrintWriter pw) {
            pw.println("  USB Device State:");
            pw.println("    Current Functions: " + mCurrentFunctions);
            pw.println("    mConnected: " + mConnected);
            pw.println("    mConfigured: " + mConfigured);
            pw.println("    mCurrentAccessory: " + mCurrentAccessory);
            try {
                pw.println("    Kernel state: "
                        + FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim());
                pw.println("    Kernel function list: "
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
        getCurrentSettings().checkPermission(accessory);
        return nativeOpenAccessory();
    }

    public void setCurrentFunctions(String functions) {
        if (DEBUG) Slog.d(TAG, "setCurrentFunctions(" + functions + ")");
        mHandler.sendMessage(MSG_SET_CURRENT_FUNCTIONS, functions);
    }

    public void setUsbDataUnlocked(boolean unlocked) {
        if (DEBUG) Slog.d(TAG, "setUsbDataUnlocked(" + unlocked + ")");
        mHandler.sendMessage(MSG_SET_USB_DATA_UNLOCKED, unlocked);
    }

    public boolean isUsbDataUnlocked() {
        if (DEBUG) Slog.d(TAG, "isUsbDataUnlocked() -> " + mHandler.mUsbDataUnlocked);
        return mHandler.mUsbDataUnlocked;
    }

    private void readOemUsbOverrideConfig() {
        String[] configList = mContext.getResources().getStringArray(
            com.android.internal.R.array.config_oemUsbModeOverride);

        if (configList != null) {
            for (String config: configList) {
                String[] items = config.split(":");
                if (items.length == 3) {
                    if (mOemModeMap == null) {
                        mOemModeMap = new HashMap<String, List<Pair<String, String>>>();
                    }
                    List<Pair<String, String>> overrideList = mOemModeMap.get(items[0]);
                    if (overrideList == null) {
                        overrideList = new LinkedList<Pair<String, String>>();
                        mOemModeMap.put(items[0], overrideList);
                    }
                    overrideList.add(new Pair<String, String>(items[1], items[2]));
                }
            }
        }
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

    public void dump(FileDescriptor fd, PrintWriter pw) {
        if (mHandler != null) {
            mHandler.dump(fd, pw);
        }
        if (mDebuggingManager != null) {
            mDebuggingManager.dump(fd, pw);
        }
    }

    private native String[] nativeGetAccessoryStrings();
    private native ParcelFileDescriptor nativeOpenAccessory();
    private native boolean nativeIsStartRequested();
    private native int nativeGetAudioMode();
}
