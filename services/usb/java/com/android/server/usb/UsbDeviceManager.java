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

import static com.android.internal.usb.DumpUtils.writeAccessory;
import static com.android.internal.util.dump.DumpUtils.writeStringIfNotNull;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.KeyguardManager;
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
import android.content.SharedPreferences;
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
import android.hardware.usb.gadget.V1_0.GadgetFunction;
import android.hardware.usb.gadget.V1_0.IUsbGadget;
import android.hardware.usb.gadget.V1_0.IUsbGadgetCallback;
import android.hardware.usb.gadget.V1_0.Status;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.service.usb.UsbDeviceManagerProto;
import android.service.usb.UsbHandlerProto;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;
import com.android.server.LocalServices;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;

/**
 * UsbDeviceManager manages USB state in device mode.
 */
public class UsbDeviceManager implements ActivityManagerInternal.ScreenObserver {

    private static final String TAG = UsbDeviceManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    /**
     * The name of the xml file in which screen unlocked functions are stored.
     */
    private static final String USB_PREFS_XML = "UsbDeviceManagerPrefs.xml";

    /**
     * The SharedPreference setting per user that stores the screen unlocked functions between
     * sessions.
     */
    static final String UNLOCKED_CONFIG_PREF = "usb-screen-unlocked-config-%d";

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
    private static final int MSG_SET_SCREEN_UNLOCKED_FUNCTIONS = 12;
    private static final int MSG_UPDATE_SCREEN_LOCK = 13;
    private static final int MSG_SET_CHARGING_FUNCTIONS = 14;
    private static final int MSG_SET_FUNCTIONS_TIMEOUT = 15;
    private static final int MSG_GET_CURRENT_USB_FUNCTIONS = 16;
    private static final int MSG_FUNCTION_SWITCH_TIMEOUT = 17;

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

    private final Object mLock = new Object();

    private final Context mContext;
    private final ContentResolver mContentResolver;
    @GuardedBy("mLock")
    private UsbProfileGroupSettingsManager mCurrentSettings;
    private final boolean mHasUsbAccessory;
    @GuardedBy("mLock")
    private String[] mAccessoryStrings;
    private UsbDebuggingManager mDebuggingManager;
    private final UEventObserver mUEventObserver;

    private static Set<Integer> sBlackListedInterfaces;
    private HashMap<Long, FileDescriptor> mControlFds;

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
    private final class UsbUEventObserver extends UEventObserver {
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
    }

    @Override
    public void onKeyguardStateChanged(boolean isShowing) {
        int userHandle = ActivityManager.getCurrentUser();
        boolean secure = mContext.getSystemService(KeyguardManager.class)
                .isDeviceSecure(userHandle);
        if (DEBUG) {
            Slog.v(TAG, "onKeyguardStateChanged: isShowing:" + isShowing + " secure:" + secure
                    + " user:" + userHandle);
        }
        // We are unlocked when the keyguard is down or non-secure.
        mHandler.sendMessage(MSG_UPDATE_SCREEN_LOCK, (isShowing && secure));
    }

    @Override
    public void onAwakeStateChanged(boolean isAwake) {
        // ignore
    }

    /** Called when a user is unlocked. */
    public void onUnlockUser(int userHandle) {
        onKeyguardStateChanged(false);
    }

    public UsbDeviceManager(Context context, UsbAlsaManager alsaManager,
            UsbSettingsManager settingsManager) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        PackageManager pm = mContext.getPackageManager();
        mHasUsbAccessory = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);
        initRndisAddress();

        boolean halNotPresent = false;
        try {
            IUsbGadget.getService(true);
        } catch (RemoteException e) {
            Slog.e(TAG, "USB GADGET HAL present but exception thrown", e);
        } catch (NoSuchElementException e) {
            halNotPresent = true;
            Slog.i(TAG, "USB GADGET HAL not present in the device", e);
        }

        mControlFds = new HashMap<>();
        FileDescriptor mtpFd = nativeOpenControl(UsbManager.USB_FUNCTION_MTP);
        if (mtpFd == null) {
            Slog.e(TAG, "Failed to open control for mtp");
        }
        mControlFds.put(UsbManager.FUNCTION_MTP, mtpFd);
        FileDescriptor ptpFd = nativeOpenControl(UsbManager.USB_FUNCTION_PTP);
        if (ptpFd == null) {
            Slog.e(TAG, "Failed to open control for ptp");
        }
        mControlFds.put(UsbManager.FUNCTION_PTP, ptpFd);

        boolean secureAdbEnabled = SystemProperties.getBoolean("ro.adb.secure", false);
        boolean dataEncrypted = "1".equals(SystemProperties.get("vold.decrypt"));
        if (secureAdbEnabled && !dataEncrypted) {
            mDebuggingManager = new UsbDebuggingManager(context);
        }

        if (halNotPresent) {
            /**
             * Initialze the legacy UsbHandler
             */
            mHandler = new UsbHandlerLegacy(FgThread.get().getLooper(), mContext, this,
                    mDebuggingManager, alsaManager, settingsManager);
        } else {
            /**
             * Initialize HAL based UsbHandler
             */
            mHandler = new UsbHandlerHal(FgThread.get().getLooper(), mContext, this,
                    mDebuggingManager, alsaManager, settingsManager);
        }

        if (nativeIsStartRequested()) {
            if (DEBUG) Slog.d(TAG, "accessory attached at boot");
            startAccessoryMode();
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

        // Watch for USB configuration changes
        mUEventObserver = new UsbUEventObserver();
        mUEventObserver.startObserving(USB_STATE_MATCH);
        mUEventObserver.startObserving(ACCESSORY_START_MATCH);

        // register observer to listen for settings changes
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false, new AdbSettingsObserver());
    }

    UsbProfileGroupSettingsManager getCurrentSettings() {
        synchronized (mLock) {
            return mCurrentSettings;
        }
    }

    String[] getAccessoryStrings() {
        synchronized (mLock) {
            return mAccessoryStrings;
        }
    }

    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        LocalServices.getService(ActivityManagerInternal.class).registerScreenObserver(this);

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

        long functions = UsbManager.FUNCTION_NONE;
        if (enableAccessory) {
            functions |= UsbManager.FUNCTION_ACCESSORY;
        }
        if (enableAudio) {
            functions |= UsbManager.FUNCTION_AUDIO_SOURCE;
        }

        if (functions != UsbManager.FUNCTION_NONE) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_ACCESSORY_MODE_ENTER_TIMEOUT),
                    ACCESSORY_REQUEST_TIMEOUT);
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

    abstract static class UsbHandler extends Handler {

        // current USB state
        private boolean mConnected;
        private boolean mHostConnected;
        private boolean mSourcePower;
        private boolean mSinkPower;
        private boolean mConfigured;
        private boolean mAudioAccessoryConnected;
        private boolean mAudioAccessorySupported;

        private UsbAccessory mCurrentAccessory;
        private int mUsbNotificationId;
        private boolean mAdbNotificationShown;
        private boolean mUsbCharging;
        private boolean mHideUsbNotification;
        private boolean mSupportsAllCombinations;
        private boolean mScreenLocked;
        private boolean mSystemReady;
        private Intent mBroadcastedIntent;
        private boolean mPendingBootBroadcast;
        private boolean mAudioSourceEnabled;
        private boolean mMidiEnabled;
        private int mMidiCard;
        private int mMidiDevice;

        private final Context mContext;
        private final UsbDebuggingManager mDebuggingManager;
        private final UsbAlsaManager mUsbAlsaManager;
        private final UsbSettingsManager mSettingsManager;
        private NotificationManager mNotificationManager;

        protected long mScreenUnlockedFunctions;
        protected boolean mAdbEnabled;
        protected boolean mBootCompleted;
        protected boolean mCurrentFunctionsApplied;
        protected boolean mUseUsbNotification;
        protected long mCurrentFunctions;
        protected final UsbDeviceManager mUsbDeviceManager;
        protected final ContentResolver mContentResolver;
        protected SharedPreferences mSettings;
        protected int mCurrentUser;
        protected boolean mCurrentUsbFunctionsReceived;

        /**
         * The persistent property which stores whether adb is enabled or not.
         * May also contain vendor-specific default functions for testing purposes.
         */
        protected static final String USB_PERSISTENT_CONFIG_PROPERTY = "persist.sys.usb.config";

        UsbHandler(Looper looper, Context context, UsbDeviceManager deviceManager,
                UsbDebuggingManager debuggingManager, UsbAlsaManager alsaManager,
                UsbSettingsManager settingsManager) {
            super(looper);
            mContext = context;
            mDebuggingManager = debuggingManager;
            mUsbDeviceManager = deviceManager;
            mUsbAlsaManager = alsaManager;
            mSettingsManager = settingsManager;
            mContentResolver = context.getContentResolver();

            mCurrentUser = ActivityManager.getCurrentUser();
            mScreenLocked = true;

            /*
             * Use the normal bootmode persistent prop to maintain state of adb across
             * all boot modes.
             */
            mAdbEnabled = UsbHandlerLegacy.containsFunction(getSystemProperty(
                    USB_PERSISTENT_CONFIG_PROPERTY, ""), UsbManager.USB_FUNCTION_ADB);

            mSettings = getPinnedSharedPrefs(mContext);
            if (mSettings == null) {
                Slog.e(TAG, "Couldn't load shared preferences");
            } else {
                mScreenUnlockedFunctions = UsbManager.usbFunctionsFromString(
                        mSettings.getString(
                                String.format(Locale.ENGLISH, UNLOCKED_CONFIG_PREF, mCurrentUser),
                                ""));
            }

            // We do not show the USB notification if the primary volume supports mass storage.
            // The legacy mass storage UI will be used instead.
            final StorageManager storageManager = StorageManager.from(mContext);
            final StorageVolume primary = storageManager.getPrimaryVolume();

            boolean massStorageSupported = primary != null && primary.allowMassStorage();
            mUseUsbNotification = !massStorageSupported && mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_usbChargingMessage);
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

        public void sendMessage(int what, boolean arg1, boolean arg2) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.arg1 = (arg1 ? 1 : 0);
            m.arg2 = (arg2 ? 1 : 0);
            sendMessage(m);
        }

        public void sendMessageDelayed(int what, boolean arg, long delayMillis) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.arg1 = (arg ? 1 : 0);
            sendMessageDelayed(m, delayMillis);
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
            if (connected == 1) removeMessages(MSG_FUNCTION_SWITCH_TIMEOUT);
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

        private void setAdbEnabled(boolean enable) {
            if (DEBUG) Slog.d(TAG, "setAdbEnabled: " + enable);
            if (enable != mAdbEnabled) {
                mAdbEnabled = enable;

                if (enable) {
                    setSystemProperty(USB_PERSISTENT_CONFIG_PROPERTY, UsbManager.USB_FUNCTION_ADB);
                } else {
                    setSystemProperty(USB_PERSISTENT_CONFIG_PROPERTY, "");
                }

                setEnabledFunctions(mCurrentFunctions, true);
                updateAdbNotification(false);
            }

            if (mDebuggingManager != null) {
                mDebuggingManager.setAdbEnabled(mAdbEnabled);
            }
        }

        protected boolean isUsbTransferAllowed() {
            UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            return !userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER);
        }

        private void updateCurrentAccessory() {
            // We are entering accessory mode if we have received a request from the host
            // and the request has not timed out yet.
            boolean enteringAccessoryMode = hasMessages(MSG_ACCESSORY_MODE_ENTER_TIMEOUT);

            if (mConfigured && enteringAccessoryMode) {
                // successfully entered accessory mode
                String[] accessoryStrings = mUsbDeviceManager.getAccessoryStrings();
                if (accessoryStrings != null) {
                    mCurrentAccessory = new UsbAccessory(accessoryStrings);
                    Slog.d(TAG, "entering USB accessory mode: " + mCurrentAccessory);
                    // defer accessoryAttached if system is not ready
                    if (mBootCompleted) {
                        mUsbDeviceManager.getCurrentSettings().accessoryAttached(mCurrentAccessory);
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
            setEnabledFunctions(UsbManager.FUNCTION_NONE, false);

            if (mCurrentAccessory != null) {
                if (mBootCompleted) {
                    mSettingsManager.usbAccessoryRemoved(mCurrentAccessory);
                }
                mCurrentAccessory = null;
            }
        }

        protected SharedPreferences getPinnedSharedPrefs(Context context) {
            final File prefsFile = new File(
                    Environment.getDataSystemDeDirectory(UserHandle.USER_SYSTEM), USB_PREFS_XML);
            return context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
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

        protected void updateUsbStateBroadcastIfNeeded(long functions) {
            // send a sticky broadcast containing current USB state
            Intent intent = new Intent(UsbManager.ACTION_USB_STATE);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(UsbManager.USB_CONNECTED, mConnected);
            intent.putExtra(UsbManager.USB_HOST_CONNECTED, mHostConnected);
            intent.putExtra(UsbManager.USB_CONFIGURED, mConfigured);
            intent.putExtra(UsbManager.USB_DATA_UNLOCKED,
                    isUsbTransferAllowed() && isUsbDataTransferActive(mCurrentFunctions));

            long remainingFunctions = functions;
            while (remainingFunctions != 0) {
                intent.putExtra(UsbManager.usbFunctionsToString(
                        Long.highestOneBit(remainingFunctions)), true);
                remainingFunctions -= Long.highestOneBit(remainingFunctions);
            }

            // send broadcast intent only if the USB state has changed
            if (!isUsbStateChanged(intent)) {
                if (DEBUG) {
                    Slog.d(TAG, "skip broadcasting " + intent + " extras: " + intent.getExtras());
                }
                return;
            }

            if (DEBUG) Slog.d(TAG, "broadcasting " + intent + " extras: " + intent.getExtras());
            sendStickyBroadcast(intent);
            mBroadcastedIntent = intent;
        }

        protected void sendStickyBroadcast(Intent intent) {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void updateUsbFunctions() {
            updateMidiFunction();
        }

        private void updateMidiFunction() {
            boolean enabled = (mCurrentFunctions & UsbManager.FUNCTION_MIDI) != 0;
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

        private void setScreenUnlockedFunctions() {
            setEnabledFunctions(mScreenUnlockedFunctions, false);
        }

        /**
         * Returns the functions that are passed down to the low level driver once adb and
         * charging are accounted for.
         */
        long getAppliedFunctions(long functions) {
            if (functions == UsbManager.FUNCTION_NONE) {
                return getChargingFunctions();
            }
            if (mAdbEnabled) {
                return functions | UsbManager.FUNCTION_ADB;
            }
            return functions;
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
                        updateUsbStateBroadcastIfNeeded(getAppliedFunctions(mCurrentFunctions));
                    }
                    if ((mCurrentFunctions & UsbManager.FUNCTION_ACCESSORY) != 0) {
                        updateCurrentAccessory();
                    }
                    if (mBootCompleted) {
                        if (!mConnected && !hasMessages(MSG_ACCESSORY_MODE_ENTER_TIMEOUT)
                                && !hasMessages(MSG_FUNCTION_SWITCH_TIMEOUT)) {
                            // restore defaults when USB is disconnected
                            if (!mScreenLocked
                                    && mScreenUnlockedFunctions != UsbManager.FUNCTION_NONE) {
                                setScreenUnlockedFunctions();
                            } else {
                                setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                            }
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
                            updateUsbStateBroadcastIfNeeded(getAppliedFunctions(mCurrentFunctions));
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
                    long functions = (Long) msg.obj;
                    setEnabledFunctions(functions, false);
                    break;
                case MSG_SET_SCREEN_UNLOCKED_FUNCTIONS:
                    mScreenUnlockedFunctions = (Long) msg.obj;
                    if (mSettings != null) {
                        SharedPreferences.Editor editor = mSettings.edit();
                        editor.putString(String.format(Locale.ENGLISH, UNLOCKED_CONFIG_PREF,
                                mCurrentUser),
                                UsbManager.usbFunctionsToString(mScreenUnlockedFunctions));
                        editor.commit();
                    }
                    if (!mScreenLocked && mScreenUnlockedFunctions != UsbManager.FUNCTION_NONE) {
                        // If the screen is unlocked, also set current functions.
                        setScreenUnlockedFunctions();
                    }
                    break;
                case MSG_UPDATE_SCREEN_LOCK:
                    if (msg.arg1 == 1 == mScreenLocked) {
                        break;
                    }
                    mScreenLocked = msg.arg1 == 1;
                    if (!mBootCompleted) {
                        break;
                    }
                    if (mScreenLocked) {
                        if (!mConnected) {
                            setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                        }
                    } else {
                        if (mScreenUnlockedFunctions != UsbManager.FUNCTION_NONE
                                && mCurrentFunctions == UsbManager.FUNCTION_NONE) {
                            // Set the screen unlocked functions if current function is charging.
                            setScreenUnlockedFunctions();
                        }
                    }
                    break;
                case MSG_UPDATE_USER_RESTRICTIONS:
                    // Restart the USB stack if USB transfer is enabled but no longer allowed.
                    if (isUsbDataTransferActive(mCurrentFunctions) && !isUsbTransferAllowed()) {
                        setEnabledFunctions(UsbManager.FUNCTION_NONE, true);
                    }
                    break;
                case MSG_SYSTEM_READY:
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
                    mSystemReady = true;
                    finishBoot();
                    break;
                case MSG_LOCALE_CHANGED:
                    updateAdbNotification(true);
                    updateUsbNotification(true);
                    break;
                case MSG_BOOT_COMPLETED:
                    mBootCompleted = true;
                    finishBoot();
                    break;
                case MSG_USER_SWITCHED: {
                    if (mCurrentUser != msg.arg1) {
                        if (DEBUG) {
                            Slog.v(TAG, "Current user switched to " + msg.arg1);
                        }
                        mCurrentUser = msg.arg1;
                        mScreenLocked = true;
                        mScreenUnlockedFunctions = UsbManager.FUNCTION_NONE;
                        if (mSettings != null) {
                            mScreenUnlockedFunctions = UsbManager.usbFunctionsFromString(
                                    mSettings.getString(String.format(Locale.ENGLISH,
                                            UNLOCKED_CONFIG_PREF, mCurrentUser), ""));
                        }
                        setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                    }
                    break;
                }
                case MSG_ACCESSORY_MODE_ENTER_TIMEOUT: {
                    if (DEBUG) {
                        Slog.v(TAG, "Accessory mode enter timeout: " + mConnected);
                    }
                    if (!mConnected || (mCurrentFunctions & UsbManager.FUNCTION_ACCESSORY) == 0) {
                        notifyAccessoryModeExit();
                    }
                    break;
                }
            }
        }

        protected void finishBoot() {
            if (mBootCompleted && mCurrentUsbFunctionsReceived && mSystemReady) {
                if (mPendingBootBroadcast) {
                    updateUsbStateBroadcastIfNeeded(getAppliedFunctions(mCurrentFunctions));
                    mPendingBootBroadcast = false;
                }
                if (!mScreenLocked
                        && mScreenUnlockedFunctions != UsbManager.FUNCTION_NONE) {
                    setScreenUnlockedFunctions();
                } else {
                    setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                }
                if (mCurrentAccessory != null) {
                    mUsbDeviceManager.getCurrentSettings().accessoryAttached(mCurrentAccessory);
                }
                if (mDebuggingManager != null) {
                    mDebuggingManager.setAdbEnabled(mAdbEnabled);
                }

                // make sure the ADB_ENABLED setting value matches the current state
                try {
                    putGlobalSettings(mContentResolver, Settings.Global.ADB_ENABLED,
                            mAdbEnabled ? 1 : 0);
                } catch (SecurityException e) {
                    // If UserManager.DISALLOW_DEBUGGING_FEATURES is on, that this setting can't
                    // be changed.
                    Slog.d(TAG, "ADB_ENABLED is restricted.");
                }

                updateUsbNotification(false);
                updateAdbNotification(false);
                updateUsbFunctions();
            }
        }

        protected boolean isUsbDataTransferActive(long functions) {
            return (functions & UsbManager.FUNCTION_MTP) != 0
                    || (functions & UsbManager.FUNCTION_PTP) != 0;
        }

        public UsbAccessory getCurrentAccessory() {
            return mCurrentAccessory;
        }

        protected void updateUsbNotification(boolean force) {
            if (mNotificationManager == null || !mUseUsbNotification
                    || ("0".equals(getSystemProperty("persist.charging.notify", "")))) {
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
            CharSequence message = r.getText(
                    com.android.internal.R.string.usb_notification_message);
            if (mAudioAccessoryConnected && !mAudioAccessorySupported) {
                titleRes = com.android.internal.R.string.usb_unsupported_audio_accessory_title;
                id = SystemMessage.NOTE_USB_AUDIO_ACCESSORY_NOT_SUPPORTED;
            } else if (mConnected) {
                if (mCurrentFunctions == UsbManager.FUNCTION_MTP) {
                    titleRes = com.android.internal.R.string.usb_mtp_notification_title;
                    id = SystemMessage.NOTE_USB_MTP;
                } else if (mCurrentFunctions == UsbManager.FUNCTION_PTP) {
                    titleRes = com.android.internal.R.string.usb_ptp_notification_title;
                    id = SystemMessage.NOTE_USB_PTP;
                } else if (mCurrentFunctions == UsbManager.FUNCTION_MIDI) {
                    titleRes = com.android.internal.R.string.usb_midi_notification_title;
                    id = SystemMessage.NOTE_USB_MIDI;
                } else if (mCurrentFunctions == UsbManager.FUNCTION_RNDIS) {
                    titleRes = com.android.internal.R.string.usb_tether_notification_title;
                    id = SystemMessage.NOTE_USB_TETHER;
                } else if (mCurrentFunctions == UsbManager.FUNCTION_ACCESSORY) {
                    titleRes = com.android.internal.R.string.usb_accessory_notification_title;
                    id = SystemMessage.NOTE_USB_ACCESSORY;
                }
                if (mSourcePower) {
                    if (titleRes != 0) {
                        message = r.getText(
                                com.android.internal.R.string.usb_power_notification_message);
                    } else {
                        titleRes = com.android.internal.R.string.usb_supplying_notification_title;
                        id = SystemMessage.NOTE_USB_SUPPLYING;
                    }
                } else if (titleRes == 0) {
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
                    CharSequence title = r.getText(titleRes);
                    PendingIntent pi;
                    String channel;

                    if (titleRes
                            != com.android.internal.R.string
                            .usb_unsupported_audio_accessory_title) {
                        Intent intent = Intent.makeRestartActivityTask(
                                new ComponentName("com.android.settings",
                                        "com.android.settings.Settings$UsbDetailsActivity"));
                        pi = PendingIntent.getActivityAsUser(mContext, 0,
                                intent, 0, null, UserHandle.CURRENT);
                        channel = SystemNotificationChannels.USB;
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

        protected void updateAdbNotification(boolean force) {
            if (mNotificationManager == null) return;
            final int id = SystemMessage.NOTE_ADB_ACTIVE;
            final int titleRes = com.android.internal.R.string.adb_active_notification_title;

            if (mAdbEnabled && mConnected) {
                if ("0".equals(getSystemProperty("persist.adb.notify", ""))) return;

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

        private boolean isTv() {
            return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        }

        protected long getChargingFunctions() {
            // if ADB is enabled, reset functions to ADB
            // else enable MTP as usual.
            if (mAdbEnabled) {
                return UsbManager.FUNCTION_ADB;
            } else {
                return UsbManager.FUNCTION_MTP;
            }
        }

        protected void setSystemProperty(String prop, String val) {
            SystemProperties.set(prop, val);
        }

        protected String getSystemProperty(String prop, String def) {
            return SystemProperties.get(prop, def);
        }

        protected void putGlobalSettings(ContentResolver contentResolver, String setting, int val) {
            Settings.Global.putInt(contentResolver, setting, val);
        }

        public long getEnabledFunctions() {
            return mCurrentFunctions;
        }

        public long getScreenUnlockedFunctions() {
            return mScreenUnlockedFunctions;
        }

        /**
         * Dump a functions mask either as proto-enums (if dumping to proto) or a string (if dumping
         * to a print writer)
         */
        private void dumpFunctions(DualDumpOutputStream dump, String idName, long id,
                long functions) {
            // UsbHandlerProto.UsbFunction matches GadgetFunction
            for (int i = 0; i < 63; i++) {
                if ((functions & (1L << i)) != 0) {
                    if (dump.isProto()) {
                        dump.write(idName, id, 1L << i);
                    } else {
                        dump.write(idName, id, GadgetFunction.toString(1L << i));
                    }
                }
            }
        }

        public void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);

            dumpFunctions(dump, "current_functions", UsbHandlerProto.CURRENT_FUNCTIONS,
                    mCurrentFunctions);
            dump.write("current_functions_applied", UsbHandlerProto.CURRENT_FUNCTIONS_APPLIED,
                    mCurrentFunctionsApplied);
            dumpFunctions(dump, "screen_unlocked_functions",
                    UsbHandlerProto.SCREEN_UNLOCKED_FUNCTIONS, mScreenUnlockedFunctions);
            dump.write("screen_locked", UsbHandlerProto.SCREEN_LOCKED, mScreenLocked);
            dump.write("connected", UsbHandlerProto.CONNECTED, mConnected);
            dump.write("configured", UsbHandlerProto.CONFIGURED, mConfigured);
            if (mCurrentAccessory != null) {
                writeAccessory(dump, "current_accessory", UsbHandlerProto.CURRENT_ACCESSORY,
                        mCurrentAccessory);
            }
            dump.write("host_connected", UsbHandlerProto.HOST_CONNECTED, mHostConnected);
            dump.write("source_power", UsbHandlerProto.SOURCE_POWER, mSourcePower);
            dump.write("sink_power", UsbHandlerProto.SINK_POWER, mSinkPower);
            dump.write("usb_charging", UsbHandlerProto.USB_CHARGING, mUsbCharging);
            dump.write("hide_usb_notification", UsbHandlerProto.HIDE_USB_NOTIFICATION,
                    mHideUsbNotification);
            dump.write("audio_accessory_connected", UsbHandlerProto.AUDIO_ACCESSORY_CONNECTED,
                    mAudioAccessoryConnected);
            dump.write("adb_enabled", UsbHandlerProto.ADB_ENABLED, mAdbEnabled);

            try {
                writeStringIfNotNull(dump, "kernel_state", UsbHandlerProto.KERNEL_STATE,
                        FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim());
            } catch (Exception e) {
                Slog.e(TAG, "Could not read kernel state", e);
            }

            try {
                writeStringIfNotNull(dump, "kernel_function_list",
                        UsbHandlerProto.KERNEL_FUNCTION_LIST,
                        FileUtils.readTextFile(new File(FUNCTIONS_PATH), 0, null).trim());
            } catch (Exception e) {
                Slog.e(TAG, "Could not read kernel function list", e);
            }

            dump.end(token);
        }

        /**
         * Evaluates USB function policies and applies the change accordingly.
         */
        protected abstract void setEnabledFunctions(long functions, boolean forceRestart);
    }

    private static final class UsbHandlerLegacy extends UsbHandler {
        /**
         * The non-persistent property which stores the current USB settings.
         */
        private static final String USB_CONFIG_PROPERTY = "sys.usb.config";

        /**
         * The non-persistent property which stores the current USB actual state.
         */
        private static final String USB_STATE_PROPERTY = "sys.usb.state";

        private HashMap<String, HashMap<String, Pair<String, String>>> mOemModeMap;
        private String mCurrentOemFunctions;
        private String mCurrentFunctionsStr;
        private boolean mUsbDataUnlocked;

        UsbHandlerLegacy(Looper looper, Context context, UsbDeviceManager deviceManager,
                UsbDebuggingManager debuggingManager, UsbAlsaManager alsaManager,
                UsbSettingsManager settingsManager) {
            super(looper, context, deviceManager, debuggingManager, alsaManager, settingsManager);
            try {
                readOemUsbOverrideConfig(context);
                // Restore default functions.
                mCurrentOemFunctions = getSystemProperty(getPersistProp(false),
                        UsbManager.USB_FUNCTION_NONE);
                if (isNormalBoot()) {
                    mCurrentFunctionsStr = getSystemProperty(USB_CONFIG_PROPERTY,
                            UsbManager.USB_FUNCTION_NONE);
                    mCurrentFunctionsApplied = mCurrentFunctionsStr.equals(
                            getSystemProperty(USB_STATE_PROPERTY, UsbManager.USB_FUNCTION_NONE));
                } else {
                    mCurrentFunctionsStr = getSystemProperty(getPersistProp(true),
                            UsbManager.USB_FUNCTION_NONE);
                    mCurrentFunctionsApplied = getSystemProperty(USB_CONFIG_PROPERTY,
                            UsbManager.USB_FUNCTION_NONE).equals(
                            getSystemProperty(USB_STATE_PROPERTY, UsbManager.USB_FUNCTION_NONE));
                }
                mCurrentFunctions = UsbManager.FUNCTION_NONE;
                mCurrentUsbFunctionsReceived = true;

                String state = FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim();
                updateState(state);
            } catch (Exception e) {
                Slog.e(TAG, "Error initializing UsbHandler", e);
            }
        }

        private void readOemUsbOverrideConfig(Context context) {
            String[] configList = context.getResources().getStringArray(
                    com.android.internal.R.array.config_oemUsbModeOverride);

            if (configList != null) {
                for (String config : configList) {
                    String[] items = config.split(":");
                    if (items.length == 3 || items.length == 4) {
                        if (mOemModeMap == null) {
                            mOemModeMap = new HashMap<>();
                        }
                        HashMap<String, Pair<String, String>> overrideMap =
                                mOemModeMap.get(items[0]);
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

            String bootMode = getSystemProperty(BOOT_MODE_PROPERTY, "unknown");
            Slog.d(TAG, "applyOemOverride usbfunctions=" + usbFunctions + " bootmode=" + bootMode);

            Map<String, Pair<String, String>> overridesMap =
                    mOemModeMap.get(bootMode);
            // Check to ensure that the oem is not overriding in the normal
            // boot mode
            if (overridesMap != null && !(bootMode.equals(NORMAL_BOOT)
                    || bootMode.equals("unknown"))) {
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
                            newFunction = addFunction(overrideFunctions.second,
                                    UsbManager.USB_FUNCTION_ADB);
                        } else {
                            newFunction = overrideFunctions.second;
                        }
                        Slog.d(TAG, "OEM USB override persisting: " + newFunction + "in prop: "
                                + getPersistProp(false));
                        setSystemProperty(getPersistProp(false), newFunction);
                    }
                    return overrideFunctions.first;
                } else if (mAdbEnabled) {
                    String newFunction = addFunction(UsbManager.USB_FUNCTION_NONE,
                            UsbManager.USB_FUNCTION_ADB);
                    setSystemProperty(getPersistProp(false), newFunction);
                } else {
                    setSystemProperty(getPersistProp(false), UsbManager.USB_FUNCTION_NONE);
                }
            }
            // return passed in functions as is.
            return usbFunctions;
        }

        private boolean waitForState(String state) {
            // wait for the transition to complete.
            // give up after 1 second.
            String value = null;
            for (int i = 0; i < 20; i++) {
                // State transition is done when sys.usb.state is set to the new configuration
                value = getSystemProperty(USB_STATE_PROPERTY, "");
                if (state.equals(value)) return true;
                SystemClock.sleep(50);
            }
            Slog.e(TAG, "waitForState(" + state + ") FAILED: got " + value);
            return false;
        }

        private void setUsbConfig(String config) {
            if (DEBUG) Slog.d(TAG, "setUsbConfig(" + config + ")");
            /**
             * set the new configuration
             * we always set it due to b/23631400, where adbd was getting killed
             * and not restarted due to property timeouts on some devices
             */
            setSystemProperty(USB_CONFIG_PROPERTY, config);
        }

        @Override
        protected void setEnabledFunctions(long usbFunctions, boolean forceRestart) {
            boolean usbDataUnlocked = isUsbDataTransferActive(usbFunctions);
            if (DEBUG) {
                Slog.d(TAG, "setEnabledFunctions functions=" + usbFunctions + ", "
                        + "forceRestart=" + forceRestart + ", usbDataUnlocked=" + usbDataUnlocked);
            }

            if (usbDataUnlocked != mUsbDataUnlocked) {
                mUsbDataUnlocked = usbDataUnlocked;
                updateUsbNotification(false);
                forceRestart = true;
            }

            /**
             * Try to set the enabled functions.
             */
            final long oldFunctions = mCurrentFunctions;
            final boolean oldFunctionsApplied = mCurrentFunctionsApplied;
            if (trySetEnabledFunctions(usbFunctions, forceRestart)) {
                return;
            }

            /**
             * Didn't work.  Try to revert changes.
             * We always reapply the policy in case certain constraints changed such as
             * user restrictions independently of any other new functions we were
             * trying to activate.
             */
            if (oldFunctionsApplied && oldFunctions != usbFunctions) {
                Slog.e(TAG, "Failsafe 1: Restoring previous USB functions.");
                if (trySetEnabledFunctions(oldFunctions, false)) {
                    return;
                }
            }

            /**
             * Still didn't work.  Try to restore the default functions.
             */
            Slog.e(TAG, "Failsafe 2: Restoring default USB functions.");
            if (trySetEnabledFunctions(UsbManager.FUNCTION_NONE, false)) {
                return;
            }

            /**
             * Now we're desperate.  Ignore the default functions.
             * Try to get ADB working if enabled.
             */
            Slog.e(TAG, "Failsafe 3: Restoring empty function list (with ADB if enabled).");
            if (trySetEnabledFunctions(UsbManager.FUNCTION_NONE, false)) {
                return;
            }

            /**
             * Ouch.
             */
            Slog.e(TAG, "Unable to set any USB functions!");
        }

        private boolean isNormalBoot() {
            String bootMode = getSystemProperty(BOOT_MODE_PROPERTY, "unknown");
            return bootMode.equals(NORMAL_BOOT) || bootMode.equals("unknown");
        }

        protected String applyAdbFunction(String functions) {
            // Do not pass null pointer to the UsbManager.
            // There isn't a check there.
            if (functions == null) {
                functions = "";
            }
            if (mAdbEnabled) {
                functions = addFunction(functions, UsbManager.USB_FUNCTION_ADB);
            } else {
                functions = removeFunction(functions, UsbManager.USB_FUNCTION_ADB);
            }
            return functions;
        }

        private boolean trySetEnabledFunctions(long usbFunctions, boolean forceRestart) {
            String functions = null;
            if (usbFunctions != UsbManager.FUNCTION_NONE) {
                functions = UsbManager.usbFunctionsToString(usbFunctions);
            }
            mCurrentFunctions = usbFunctions;
            if (functions == null || applyAdbFunction(functions)
                    .equals(UsbManager.USB_FUNCTION_NONE)) {
                functions = UsbManager.usbFunctionsToString(getChargingFunctions());
            }
            functions = applyAdbFunction(functions);

            String oemFunctions = applyOemOverrideFunction(functions);

            if (!isNormalBoot() && !mCurrentFunctionsStr.equals(functions)) {
                setSystemProperty(getPersistProp(true), functions);
            }

            if ((!functions.equals(oemFunctions)
                    && !mCurrentOemFunctions.equals(oemFunctions))
                    || !mCurrentFunctionsStr.equals(functions)
                    || !mCurrentFunctionsApplied
                    || forceRestart) {
                Slog.i(TAG, "Setting USB config to " + functions);
                mCurrentFunctionsStr = functions;
                mCurrentOemFunctions = oemFunctions;
                mCurrentFunctionsApplied = false;

                /**
                 * Kick the USB stack to close existing connections.
                 */
                setUsbConfig(UsbManager.USB_FUNCTION_NONE);

                if (!waitForState(UsbManager.USB_FUNCTION_NONE)) {
                    Slog.e(TAG, "Failed to kick USB config");
                    return false;
                }

                /**
                 * Set the new USB configuration.
                 */
                setUsbConfig(oemFunctions);

                if (mBootCompleted
                        && (containsFunction(functions, UsbManager.USB_FUNCTION_MTP)
                        || containsFunction(functions, UsbManager.USB_FUNCTION_PTP))) {
                    /**
                     * Start up dependent services.
                     */
                    updateUsbStateBroadcastIfNeeded(getAppliedFunctions(mCurrentFunctions));
                }

                if (!waitForState(oemFunctions)) {
                    Slog.e(TAG, "Failed to switch USB config to " + functions);
                    return false;
                }

                mCurrentFunctionsApplied = true;
            }
            return true;
        }

        private String getPersistProp(boolean functions) {
            String bootMode = getSystemProperty(BOOT_MODE_PROPERTY, "unknown");
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

        private static String addFunction(String functions, String function) {
            if (UsbManager.USB_FUNCTION_NONE.equals(functions)) {
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
                return UsbManager.USB_FUNCTION_NONE;
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

        static boolean containsFunction(String functions, String function) {
            int index = functions.indexOf(function);
            if (index < 0) return false;
            if (index > 0 && functions.charAt(index - 1) != ',') return false;
            int charAfter = index + function.length();
            if (charAfter < functions.length() && functions.charAt(charAfter) != ',') return false;
            return true;
        }
    }

    private static final class UsbHandlerHal extends UsbHandler {

        /**
         * Proxy object for the usb gadget hal daemon.
         */
        @GuardedBy("mGadgetProxyLock")
        private IUsbGadget mGadgetProxy;

        private final Object mGadgetProxyLock = new Object();

        /**
         * Cookie sent for usb gadget hal death notification.
         */
        private static final int USB_GADGET_HAL_DEATH_COOKIE = 2000;

        /**
         * Keeps track of the latest setCurrentUsbFunctions request number.
         */
        private int mCurrentRequest = 0;

        /**
         * The maximum time for which the UsbDeviceManager would wait once
         * setCurrentUsbFunctions is called.
         */
        private static final int SET_FUNCTIONS_TIMEOUT_MS = 3000;

        /**
         * Conseration leeway to make sure that the hal callback arrives before
         * SET_FUNCTIONS_TIMEOUT_MS expires. If the callback does not arrive
         * within SET_FUNCTIONS_TIMEOUT_MS, UsbDeviceManager retries enabling
         * default functions.
         */
        private static final int SET_FUNCTIONS_LEEWAY_MS = 500;

        /**
         * While switching functions, a disconnect is excpect as the usb gadget
         * us torn down and brought back up. Wait for SET_FUNCTIONS_TIMEOUT_MS +
         * ENUMERATION_TIME_OUT_MS before switching back to default fumctions when
         * switching functions.
         */
        private static final int ENUMERATION_TIME_OUT_MS = 2000;

        /**
         * Command to start native service.
         */
        protected static final String CTL_START = "ctl.start";

        /**
         * Command to start native service.
         */
        protected static final String CTL_STOP = "ctl.stop";

        /**
         * Adb natvie daemon
         */
        protected static final String ADBD = "adbd";

        protected boolean mCurrentUsbFunctionsRequested;

        UsbHandlerHal(Looper looper, Context context, UsbDeviceManager deviceManager,
                UsbDebuggingManager debuggingManager, UsbAlsaManager alsaManager,
                UsbSettingsManager settingsManager) {
            super(looper, context, deviceManager, debuggingManager, alsaManager, settingsManager);
            try {
                ServiceNotification serviceNotification = new ServiceNotification();

                boolean ret = IServiceManager.getService()
                        .registerForNotifications("android.hardware.usb.gadget@1.0::IUsbGadget",
                                "", serviceNotification);
                if (!ret) {
                    Slog.e(TAG, "Failed to register usb gadget service start notification");
                    return;
                }

                synchronized (mGadgetProxyLock) {
                    mGadgetProxy = IUsbGadget.getService(true);
                    mGadgetProxy.linkToDeath(new UsbGadgetDeathRecipient(),
                            USB_GADGET_HAL_DEATH_COOKIE);
                    mCurrentFunctions = UsbManager.FUNCTION_NONE;
                    mGadgetProxy.getCurrentUsbFunctions(new UsbGadgetCallback());
                    mCurrentUsbFunctionsRequested = true;
                }
                String state = FileUtils.readTextFile(new File(STATE_PATH), 0, null).trim();
                updateState(state);
            } catch (NoSuchElementException e) {
                Slog.e(TAG, "Usb gadget hal not found", e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Usb Gadget hal not responding", e);
            } catch (Exception e) {
                Slog.e(TAG, "Error initializing UsbHandler", e);
            }
        }


        final class UsbGadgetDeathRecipient implements HwBinder.DeathRecipient {
            @Override
            public void serviceDied(long cookie) {
                if (cookie == USB_GADGET_HAL_DEATH_COOKIE) {
                    Slog.e(TAG, "Usb Gadget hal service died cookie: " + cookie);
                    synchronized (mGadgetProxyLock) {
                        mGadgetProxy = null;
                    }
                }
            }
        }

        final class ServiceNotification extends IServiceNotification.Stub {
            @Override
            public void onRegistration(String fqName, String name, boolean preexisting) {
                Slog.i(TAG, "Usb gadget hal service started " + fqName + " " + name);
                synchronized (mGadgetProxyLock) {
                    try {
                        mGadgetProxy = IUsbGadget.getService();
                        mGadgetProxy.linkToDeath(new UsbGadgetDeathRecipient(),
                                USB_GADGET_HAL_DEATH_COOKIE);
                        if (!mCurrentFunctionsApplied && !mCurrentUsbFunctionsRequested) {
                            setEnabledFunctions(mCurrentFunctions, false);
                        }
                    } catch (NoSuchElementException e) {
                        Slog.e(TAG, "Usb gadget hal not found", e);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Usb Gadget hal not responding", e);
                    }
                }
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CHARGING_FUNCTIONS:
                    setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                    break;
                case MSG_SET_FUNCTIONS_TIMEOUT:
                    Slog.e(TAG, "Set functions timed out! no reply from usb hal");
                    if (msg.arg1 != 1) {
                        setEnabledFunctions(UsbManager.FUNCTION_NONE, false);
                    }
                    break;
                case MSG_GET_CURRENT_USB_FUNCTIONS:
                    Slog.e(TAG, "prcessing MSG_GET_CURRENT_USB_FUNCTIONS");
                    mCurrentUsbFunctionsReceived = true;

                    if (mCurrentUsbFunctionsRequested) {
                        Slog.e(TAG, "updating mCurrentFunctions");
                        // Mask out adb, since it is stored in mAdbEnabled
                        mCurrentFunctions = ((Long) msg.obj) & ~UsbManager.FUNCTION_ADB;
                        Slog.e(TAG,
                                "mCurrentFunctions:" + mCurrentFunctions + "applied:" + msg.arg1);
                        mCurrentFunctionsApplied = msg.arg1 == 1;
                    }
                    finishBoot();
                    break;
                case MSG_FUNCTION_SWITCH_TIMEOUT:
                    /**
                     * Dont force to default when the configuration is already set to default.
                     */
                    if (msg.arg1 != 1) {
                        setEnabledFunctions(UsbManager.FUNCTION_NONE, !mAdbEnabled);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        private class UsbGadgetCallback extends IUsbGadgetCallback.Stub {
            int mRequest;
            long mFunctions;
            boolean mChargingFunctions;

            UsbGadgetCallback() {
            }

            UsbGadgetCallback(int request, long functions,
                    boolean chargingFunctions) {
                mRequest = request;
                mFunctions = functions;
                mChargingFunctions = chargingFunctions;
            }

            @Override
            public void setCurrentUsbFunctionsCb(long functions,
                    int status) {
                /**
                 * Callback called for a previous setCurrenUsbFunction
                 */
                if ((mCurrentRequest != mRequest) || !hasMessages(MSG_SET_FUNCTIONS_TIMEOUT)
                        || (mFunctions != functions)) {
                    return;
                }

                removeMessages(MSG_SET_FUNCTIONS_TIMEOUT);
                Slog.e(TAG, "notifyCurrentFunction request:" + mRequest + " status:" + status);
                if (status == Status.SUCCESS) {
                    mCurrentFunctionsApplied = true;
                } else if (!mChargingFunctions) {
                    Slog.e(TAG, "Setting default fuctions");
                    sendEmptyMessage(MSG_SET_CHARGING_FUNCTIONS);
                }
            }

            @Override
            public void getCurrentUsbFunctionsCb(long functions,
                    int status) {
                sendMessage(MSG_GET_CURRENT_USB_FUNCTIONS, functions,
                        status == Status.FUNCTIONS_APPLIED);
            }
        }

        private void setUsbConfig(long config, boolean chargingFunctions) {
            if (true) Slog.d(TAG, "setUsbConfig(" + config + ") request:" + ++mCurrentRequest);
            /**
             * Cancel any ongoing requests, if present.
             */
            removeMessages(MSG_FUNCTION_SWITCH_TIMEOUT);
            removeMessages(MSG_SET_FUNCTIONS_TIMEOUT);
            removeMessages(MSG_SET_CHARGING_FUNCTIONS);

            synchronized (mGadgetProxyLock) {
                if (mGadgetProxy == null) {
                    Slog.e(TAG, "setUsbConfig mGadgetProxy is null");
                    return;
                }
                try {
                    if ((config & UsbManager.FUNCTION_ADB) != 0) {
                        /**
                         * Start adbd if ADB function is included in the configuration.
                         */
                        setSystemProperty(CTL_START, ADBD);
                    } else {
                        /**
                         * Stop adbd otherwise.
                         */
                        setSystemProperty(CTL_STOP, ADBD);
                    }
                    UsbGadgetCallback usbGadgetCallback = new UsbGadgetCallback(mCurrentRequest,
                            config, chargingFunctions);
                    mGadgetProxy.setCurrentUsbFunctions(config, usbGadgetCallback,
                            SET_FUNCTIONS_TIMEOUT_MS - SET_FUNCTIONS_LEEWAY_MS);
                    sendMessageDelayed(MSG_SET_FUNCTIONS_TIMEOUT, chargingFunctions,
                            SET_FUNCTIONS_TIMEOUT_MS);
                    sendMessageDelayed(MSG_FUNCTION_SWITCH_TIMEOUT, chargingFunctions,
                            SET_FUNCTIONS_TIMEOUT_MS + ENUMERATION_TIME_OUT_MS);
                    if (DEBUG) Slog.d(TAG, "timeout message queued");
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remoteexception while calling setCurrentUsbFunctions", e);
                }
            }
        }

        @Override
        protected void setEnabledFunctions(long functions, boolean forceRestart) {
            if (DEBUG) {
                Slog.d(TAG, "setEnabledFunctions functions=" + functions + ", "
                        + "forceRestart=" + forceRestart);
            }
            if (mCurrentFunctions != functions
                    || !mCurrentFunctionsApplied
                    || forceRestart) {
                Slog.i(TAG, "Setting USB config to " + UsbManager.usbFunctionsToString(functions));
                mCurrentFunctions = functions;
                mCurrentFunctionsApplied = false;
                // set the flag to false as that would be stale value
                mCurrentUsbFunctionsRequested = false;

                boolean chargingFunctions = functions == UsbManager.FUNCTION_NONE;
                functions = getAppliedFunctions(functions);

                // Set the new USB configuration.
                setUsbConfig(functions, chargingFunctions);

                if (mBootCompleted && isUsbDataTransferActive(functions)) {
                    // Start up dependent services.
                    updateUsbStateBroadcastIfNeeded(functions);
                }
            }
        }
    }

    /* returns the currently attached USB accessory */
    public UsbAccessory getCurrentAccessory() {
        return mHandler.getCurrentAccessory();
    }

    /**
     * opens the currently attached USB accessory.
     *
     * @param accessory accessory to be openened.
     */
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

    public long getCurrentFunctions() {
        return mHandler.getEnabledFunctions();
    }

    /**
     * Returns a dup of the control file descriptor for the given function.
     */
    public ParcelFileDescriptor getControlFd(long usbFunction) {
        FileDescriptor fd = mControlFds.get(usbFunction);
        if (fd == null) {
            return null;
        }
        try {
            return ParcelFileDescriptor.dup(fd);
        } catch (IOException e) {
            Slog.e(TAG, "Could not dup fd for " + usbFunction);
            return null;
        }
    }

    public long getScreenUnlockedFunctions() {
        return mHandler.getScreenUnlockedFunctions();
    }

    /**
     * Adds function to the current USB configuration.
     *
     * @param functions The functions to set, or empty to set the charging function.
     */
    public void setCurrentFunctions(long functions) {
        if (DEBUG) {
            Slog.d(TAG, "setCurrentFunctions(" + UsbManager.usbFunctionsToString(functions) + ")");
        }
        if (functions == UsbManager.FUNCTION_NONE) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_CHARGING);
        } else if (functions == UsbManager.FUNCTION_MTP) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_MTP);
        } else if (functions == UsbManager.FUNCTION_PTP) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_PTP);
        } else if (functions == UsbManager.FUNCTION_MIDI) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_MIDI);
        } else if (functions == UsbManager.FUNCTION_RNDIS) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_RNDIS);
        } else if (functions == UsbManager.FUNCTION_ACCESSORY) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_USB_CONFIG_ACCESSORY);
        }
        mHandler.sendMessage(MSG_SET_CURRENT_FUNCTIONS, functions);
    }

    /**
     * Sets the functions which are set when the screen is unlocked.
     *
     * @param functions Functions to set.
     */
    public void setScreenUnlockedFunctions(long functions) {
        if (DEBUG) {
            Slog.d(TAG, "setScreenUnlockedFunctions("
                    + UsbManager.usbFunctionsToString(functions) + ")");
        }
        mHandler.sendMessage(MSG_SET_SCREEN_UNLOCKED_FUNCTIONS, functions);
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

    /**
     * Write the state to a dump stream.
     */
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        if (mHandler != null) {
            mHandler.dump(dump, "handler", UsbDeviceManagerProto.HANDLER);
        }
        if (mDebuggingManager != null) {
            mDebuggingManager.dump(dump, "debugging_manager",
                    UsbDeviceManagerProto.DEBUGGING_MANAGER);
        }

        dump.end(token);
    }

    private native String[] nativeGetAccessoryStrings();

    private native ParcelFileDescriptor nativeOpenAccessory();

    private native FileDescriptor nativeOpenControl(String usbFunction);

    private native boolean nativeIsStartRequested();

    private native int nativeGetAudioMode();
}
