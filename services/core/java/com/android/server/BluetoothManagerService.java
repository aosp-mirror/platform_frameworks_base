/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.os.UserHandle.USER_SYSTEM;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothHeadset;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothProfileServiceConnection;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.UserManagerInternal.UserRestrictionsListener;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.pm.UserRestrictionsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    private static final String BLUETOOTH_PRIVILEGED =
            android.Manifest.permission.BLUETOOTH_PRIVILEGED;

    private static final String SECURE_SETTINGS_BLUETOOTH_ADDR_VALID = "bluetooth_addr_valid";
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDRESS = "bluetooth_address";
    private static final String SECURE_SETTINGS_BLUETOOTH_NAME = "bluetooth_name";

    private static final int ACTIVE_LOG_MAX_SIZE = 20;
    private static final int CRASH_LOG_MAX_SIZE = 100;

    private static final int TIMEOUT_BIND_MS = 3000; //Maximum msec to wait for a bind
    //Maximum msec to wait for service restart
    private static final int SERVICE_RESTART_TIME_MS = 400;
    //Maximum msec to wait for restart due to error
    private static final int ERROR_RESTART_TIME_MS = 3000;
    //Maximum msec to delay MESSAGE_USER_SWITCHED
    private static final int USER_SWITCHED_TIME_MS = 200;
    // Delay for the addProxy function in msec
    private static final int ADD_PROXY_DELAY_MS = 100;
    // Delay for retrying enable and disable in msec
    private static final int ENABLE_DISABLE_DELAY_MS = 300;
    private static final int DELAY_BEFORE_RESTART_DUE_TO_INIT_FLAGS_CHANGED_MS = 300;
    private static final int DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS = 86400;

    private static final int MESSAGE_ENABLE = 1;
    private static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_HANDLE_ENABLE_DELAYED = 3;
    private static final int MESSAGE_HANDLE_DISABLE_DELAYED = 4;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    private static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    private static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    private static final int MESSAGE_TIMEOUT_BIND = 100;
    private static final int MESSAGE_TIMEOUT_UNBIND = 101;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS = 200;
    private static final int MESSAGE_USER_SWITCHED = 300;
    private static final int MESSAGE_USER_UNLOCKED = 301;
    private static final int MESSAGE_ADD_PROXY_DELAYED = 400;
    private static final int MESSAGE_BIND_PROFILE_SERVICE = 401;
    private static final int MESSAGE_RESTORE_USER_SETTING = 500;
    private static final int MESSAGE_INIT_FLAGS_CHANGED = 600;

    private static final int RESTORE_SETTING_TO_ON = 1;
    private static final int RESTORE_SETTING_TO_OFF = 0;

    private static final int MAX_ERROR_RESTART_RETRIES = 6;
    private static final int MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES = 10;

    // Bluetooth persisted setting is off
    private static final int BLUETOOTH_OFF = 0;
    // Bluetooth persisted setting is on
    // and Airplane mode won't affect Bluetooth state at start up
    private static final int BLUETOOTH_ON_BLUETOOTH = 1;
    // Bluetooth persisted setting is on
    // but Airplane mode will affect Bluetooth state at start up
    // and Airplane mode will have higher priority.
    @VisibleForTesting
    static final int BLUETOOTH_ON_AIRPLANE = 2;

    private static final int SERVICE_IBLUETOOTH = 1;
    private static final int SERVICE_IBLUETOOTHGATT = 2;

    private final Context mContext;

    // Locks are not provided for mName and mAddress.
    // They are accessed in handler or broadcast receiver, same thread context.
    private String mAddress;
    private String mName;
    private final ContentResolver mContentResolver;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks;
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private IBinder mBluetoothBinder;
    private IBluetooth mBluetooth;
    private IBluetoothGatt mBluetoothGatt;
    private final ReentrantReadWriteLock mBluetoothLock = new ReentrantReadWriteLock();
    private boolean mBinding;
    private boolean mUnbinding;
    private int mWaitForEnableRetry;
    private int mWaitForDisableRetry;

    private BluetoothModeChangeHelper mBluetoothModeChangeHelper;

    private BluetoothAirplaneModeListener mBluetoothAirplaneModeListener;

    private BluetoothDeviceConfigListener mBluetoothDeviceConfigListener;

    // used inside handler thread
    private boolean mQuietEnable = false;
    private boolean mEnable;

    private static CharSequence timeToLog(long timestamp) {
        return android.text.format.DateFormat.format("MM-dd HH:mm:ss", timestamp);
    }

    /**
     * Used for tracking apps that enabled / disabled Bluetooth.
     */
    private class ActiveLog {
        private int mReason;
        private String mPackageName;
        private boolean mEnable;
        private long mTimestamp;

        ActiveLog(int reason, String packageName, boolean enable, long timestamp) {
            mReason = reason;
            mPackageName = packageName;
            mEnable = enable;
            mTimestamp = timestamp;
        }

        public String toString() {
            return timeToLog(mTimestamp) + (mEnable ? "  Enabled " : " Disabled ")
                    + " due to " + getEnableDisableReasonString(mReason) + " by " + mPackageName;
        }

        void dump(ProtoOutputStream proto) {
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.TIMESTAMP_MS, mTimestamp);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.ENABLE, mEnable);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.PACKAGE_NAME, mPackageName);
            proto.write(BluetoothManagerServiceDumpProto.ActiveLog.REASON, mReason);
        }
    }

    private final LinkedList<ActiveLog> mActiveLogs = new LinkedList<>();
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private int mCrashes;
    private long mLastEnabledTime;

    // configuration from external IBinder call which is used to
    // synchronize with broadcast receiver.
    private boolean mQuietEnableExternal;
    private boolean mEnableExternal;

    // Map of apps registered to keep BLE scanning on.
    private Map<IBinder, ClientDeathRecipient> mBleApps =
            new ConcurrentHashMap<IBinder, ClientDeathRecipient>();

    private int mState;
    private final BluetoothHandler mHandler;
    private int mErrorRecoveryRetryCounter;
    private final int mSystemUiUid;

    private boolean mIsHearingAidProfileSupported;

    private AppOpsManager mAppOps;

    // Save a ProfileServiceConnections object for each of the bound
    // bluetooth profile services
    private final Map<Integer, ProfileServiceConnections> mProfileServices = new HashMap<>();

    private final boolean mWirelessConsentRequired;

    private final IBluetoothCallback mBluetoothCallback = new IBluetoothCallback.Stub() {
        @Override
        public void onBluetoothStateChange(int prevState, int newState) throws RemoteException {
            Message msg =
                    mHandler.obtainMessage(MESSAGE_BLUETOOTH_STATE_CHANGE, prevState, newState);
            mHandler.sendMessage(msg);
        }
    };

    private final UserRestrictionsListener mUserRestrictionsListener =
            new UserRestrictionsListener() {
                @Override
                public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                        Bundle prevRestrictions) {

                    if (UserRestrictionsUtils.restrictionsChanged(prevRestrictions, newRestrictions,
                            UserManager.DISALLOW_BLUETOOTH_SHARING)) {
                        updateOppLauncherComponentState(userId,
                                newRestrictions.getBoolean(UserManager.DISALLOW_BLUETOOTH_SHARING));
                    }

                    // DISALLOW_BLUETOOTH can only be set by DO or PO on the system user.
                    if (userId == USER_SYSTEM
                            && UserRestrictionsUtils.restrictionsChanged(prevRestrictions,
                            newRestrictions, UserManager.DISALLOW_BLUETOOTH)) {
                        if (userId == USER_SYSTEM && newRestrictions.getBoolean(
                                UserManager.DISALLOW_BLUETOOTH)) {
                            updateOppLauncherComponentState(userId, true); // Sharing disallowed
                            sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED,
                                    mContext.getPackageName());
                        } else {
                            updateOppLauncherComponentState(userId, newRestrictions.getBoolean(
                                    UserManager.DISALLOW_BLUETOOTH_SHARING));
                        }
                    }
                }
            };

    @VisibleForTesting
    public void onInitFlagsChanged() {
        mHandler.removeMessages(MESSAGE_INIT_FLAGS_CHANGED);
        mHandler.sendEmptyMessageDelayed(
                MESSAGE_INIT_FLAGS_CHANGED,
                DELAY_BEFORE_RESTART_DUE_TO_INIT_FLAGS_CHANGED_MS);
    }

    public boolean onFactoryReset() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                "Need BLUETOOTH_PRIVILEGED permission");

        // Wait for stable state if bluetooth is temporary state.
        int state = getState();
        if (state == BluetoothAdapter.STATE_BLE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_ON
                || state == BluetoothAdapter.STATE_TURNING_OFF) {
            if (!waitForState(Set.of(BluetoothAdapter.STATE_BLE_ON, BluetoothAdapter.STATE_ON))) {
                return false;
            }
        }

        // Clear registered LE apps to force shut-off Bluetooth
        clearBleApps();
        state = getState();
        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth == null) {
                return false;
            }
            if (state == BluetoothAdapter.STATE_BLE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(), false);
                mBluetooth.onBrEdrDown();
                return true;
            } else if (state == BluetoothAdapter.STATE_ON) {
                addActiveLog(
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET,
                        mContext.getPackageName(), false);
                mBluetooth.disable();
                return true;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to shutdown Bluetooth", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
        return false;
    }

    public void onAirplaneModeChanged() {
        synchronized (this) {
            if (isBluetoothPersistedStateOn()) {
                if (isAirplaneModeOn()) {
                    persistBluetoothSetting(BLUETOOTH_ON_AIRPLANE);
                } else {
                    persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                }
            }

            int st = BluetoothAdapter.STATE_OFF;
            try {
                mBluetoothLock.readLock().lock();
                if (mBluetooth != null) {
                    st = mBluetooth.getState();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to call getState", e);
                return;
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            Slog.d(TAG,
                    "Airplane Mode change - current state:  " + BluetoothAdapter.nameForState(
                            st) + ", isAirplaneModeOn()=" + isAirplaneModeOn());

            if (isAirplaneModeOn()) {
                // Clear registered LE apps to force shut-off
                clearBleApps();

                // If state is BLE_ON make sure we trigger disableBLE
                if (st == BluetoothAdapter.STATE_BLE_ON) {
                    try {
                        mBluetoothLock.readLock().lock();
                        if (mBluetooth != null) {
                            addActiveLog(
                                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                                    mContext.getPackageName(), false);
                            mBluetooth.onBrEdrDown();
                            mEnable = false;
                            mEnableExternal = false;
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to call onBrEdrDown", e);
                    } finally {
                        mBluetoothLock.readLock().unlock();
                    }
                } else if (st == BluetoothAdapter.STATE_ON) {
                    sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                            mContext.getPackageName());
                }
            } else if (mEnableExternal) {
                sendEnableMsg(mQuietEnableExternal,
                        BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE,
                        mContext.getPackageName());
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                if (DBG) {
                    Slog.d(TAG, "Bluetooth Adapter name changed to " + newName + " by "
                            + mContext.getPackageName());
                }
                if (newName != null) {
                    storeNameAndAddress(newName, null);
                }
            } else if (BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED.equals(action)) {
                String newAddress = intent.getStringExtra(BluetoothAdapter.EXTRA_BLUETOOTH_ADDRESS);
                if (newAddress != null) {
                    if (DBG) {
                        Slog.d(TAG, "Bluetooth Adapter address changed to " + newAddress);
                    }
                    storeNameAndAddress(null, newAddress);
                } else {
                    if (DBG) {
                        Slog.e(TAG, "No Bluetooth Adapter address parameter found");
                    }
                }
            } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                final String name = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                if (Settings.Global.BLUETOOTH_ON.equals(name)) {
                    // The Bluetooth On state may be changed during system restore.
                    final String prevValue =
                            intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                    final String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);

                    if (DBG) {
                        Slog.d(TAG,
                                "ACTION_SETTING_RESTORED with BLUETOOTH_ON, prevValue=" + prevValue
                                        + ", newValue=" + newValue);
                    }

                    if ((newValue != null) && (prevValue != null) && !prevValue.equals(newValue)) {
                        Message msg = mHandler.obtainMessage(MESSAGE_RESTORE_USER_SETTING,
                                newValue.equals("0") ? RESTORE_SETTING_TO_OFF
                                        : RESTORE_SETTING_TO_ON, 0);
                        mHandler.sendMessage(msg);
                    }
                }
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_CONNECTED);
                if (mHandler.hasMessages(MESSAGE_INIT_FLAGS_CHANGED)
                        && state == BluetoothProfile.STATE_DISCONNECTED
                        && !mBluetoothModeChangeHelper.isA2dpOrHearingAidConnected()) {
                    Slog.i(TAG, "Device disconnected, reactivating pending flag changes");
                    onInitFlagsChanged();
                }
            }
        }
    };

    BluetoothManagerService(Context context) {
        mHandler = new BluetoothHandler(IoThread.get().getLooper());

        mContext = context;

        mWirelessConsentRequired = context.getResources()
                .getBoolean(com.android.internal.R.bool.config_wirelessConsentRequired);

        mCrashes = 0;
        mBluetooth = null;
        mBluetoothBinder = null;
        mBluetoothGatt = null;
        mBinding = false;
        mUnbinding = false;
        mEnable = false;
        mState = BluetoothAdapter.STATE_OFF;
        mQuietEnableExternal = false;
        mEnableExternal = false;
        mAddress = null;
        mName = null;
        mErrorRecoveryRetryCounter = 0;
        mContentResolver = context.getContentResolver();
        // Observe BLE scan only mode settings change.
        registerForBleScanModeChange();
        mCallbacks = new RemoteCallbackList<IBluetoothManagerCallback>();
        mStateChangeCallbacks = new RemoteCallbackList<IBluetoothStateChangeCallback>();

        mIsHearingAidProfileSupported = context.getResources()
                .getBoolean(com.android.internal.R.bool.config_hearing_aid_profile_supported);

        // TODO: We need a more generic way to initialize the persist keys of FeatureFlagUtils
        String value = SystemProperties.get(FeatureFlagUtils.PERSIST_PREFIX + FeatureFlagUtils.HEARING_AID_SETTINGS);
        if (!TextUtils.isEmpty(value)) {
            boolean isHearingAidEnabled = Boolean.parseBoolean(value);
            Log.v(TAG, "set feature flag HEARING_AID_SETTINGS to " + isHearingAidEnabled);
            FeatureFlagUtils.setEnabled(context, FeatureFlagUtils.HEARING_AID_SETTINGS, isHearingAidEnabled);
            if (isHearingAidEnabled && !mIsHearingAidProfileSupported) {
                // Overwrite to enable support by FeatureFlag
                mIsHearingAidProfileSupported = true;
            }
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED);
        filter.addAction(Intent.ACTION_SETTING_RESTORED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter);

        loadStoredNameAndAddress();
        if (isBluetoothPersistedStateOn()) {
            if (DBG) {
                Slog.d(TAG, "Startup: Bluetooth persisted state is ON.");
            }
            mEnableExternal = true;
        }

        String airplaneModeRadios =
                Settings.Global.getString(mContentResolver, Settings.Global.AIRPLANE_MODE_RADIOS);
        if (airplaneModeRadios == null || airplaneModeRadios.contains(
                Settings.Global.RADIO_BLUETOOTH)) {
            mBluetoothAirplaneModeListener = new BluetoothAirplaneModeListener(
                    this, IoThread.get().getLooper(), context);
        }

        int systemUiUid = -1;
        // Check if device is configured with no home screen, which implies no SystemUI.
        boolean noHome = mContext.getResources().getBoolean(R.bool.config_noHomeScreen);
        if (!noHome) {
            PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
            systemUiUid = pm.getPackageUid(pm.getSystemUiServiceComponent().getPackageName(),
                    MATCH_SYSTEM_ONLY, USER_SYSTEM);
        }
        if (systemUiUid >= 0) {
            Slog.d(TAG, "Detected SystemUiUid: " + Integer.toString(systemUiUid));
        } else {
            // Some platforms, such as wearables do not have a system ui.
            Slog.w(TAG, "Unable to resolve SystemUI's UID.");
        }
        mSystemUiUid = systemUiUid;
    }

    /**
     *  Returns true if airplane mode is currently on
     */
    private boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private boolean supportBluetoothPersistedState() {
        return mContext.getResources().getBoolean(R.bool.config_supportBluetoothPersistedState);
    }

    /**
     *  Returns true if the Bluetooth saved state is "on"
     */
    private boolean isBluetoothPersistedStateOn() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        int state = Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON, -1);
        if (DBG) {
            Slog.d(TAG, "Bluetooth persisted state: " + state);
        }
        return state != BLUETOOTH_OFF;
    }

    private boolean isBluetoothPersistedStateOnAirplane() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        int state = Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON, -1);
        if (DBG) {
            Slog.d(TAG, "Bluetooth persisted state: " + state);
        }
        return state == BLUETOOTH_ON_AIRPLANE;
    }

    /**
     *  Returns true if the Bluetooth saved state is BLUETOOTH_ON_BLUETOOTH
     */
    private boolean isBluetoothPersistedStateOnBluetooth() {
        if (!supportBluetoothPersistedState()) {
            return false;
        }
        return Settings.Global.getInt(mContentResolver, Settings.Global.BLUETOOTH_ON,
                BLUETOOTH_ON_BLUETOOTH) == BLUETOOTH_ON_BLUETOOTH;
    }

    /**
     *  Save the Bluetooth on/off state
     */
    private void persistBluetoothSetting(int value) {
        if (DBG) {
            Slog.d(TAG, "Persisting Bluetooth Setting: " + value);
        }
        // waive WRITE_SECURE_SETTINGS permission check
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.BLUETOOTH_ON, value);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Returns true if the Bluetooth Adapter's name and address is
     * locally cached
     * @return
     */
    private boolean isNameAndAddressSet() {
        return mName != null && mAddress != null && mName.length() > 0 && mAddress.length() > 0;
    }

    /**
     * Retrieve the Bluetooth Adapter's name and address and save it in
     * in the local cache
     */
    private void loadStoredNameAndAddress() {
        if (DBG) {
            Slog.d(TAG, "Loading stored name and address");
        }
        if (mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_bluetooth_address_validation)
                && Settings.Secure.getInt(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 0)
                == 0) {
            // if the valid flag is not set, don't load the address and name
            if (DBG) {
                Slog.d(TAG, "invalid bluetooth name and address stored");
            }
            return;
        }
        mName = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME);
        mAddress = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS);
        if (DBG) {
            Slog.d(TAG, "Stored bluetooth Name=" + mName + ",Address=" + mAddress);
        }
    }

    /**
     * Save the Bluetooth name and address in the persistent store.
     * Only non-null values will be saved.
     * @param name
     * @param address
     */
    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME, name);
            mName = name;
            if (DBG) {
                Slog.d(TAG, "Stored Bluetooth name: " + Settings.Secure.getString(mContentResolver,
                        SECURE_SETTINGS_BLUETOOTH_NAME));
            }
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS, address);
            mAddress = address;
            if (DBG) {
                Slog.d(TAG,
                        "Stored Bluetoothaddress: " + Settings.Secure.getString(mContentResolver,
                                SECURE_SETTINGS_BLUETOOTH_ADDRESS));
            }
        }

        if ((name != null) && (address != null)) {
            Settings.Secure.putInt(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 1);
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Slog.w(TAG, "Callback is null in registerAdapter");
            return null;
        }
        synchronized (mCallbacks) {
            mCallbacks.register(callback);
        }
        return mBluetooth;
    }

    public void unregisterAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Slog.w(TAG, "Callback is null in unregisterAdapter");
            return;
        }
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mCallbacks) {
            mCallbacks.unregister(callback);
        }
    }

    public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (callback == null) {
            Slog.w(TAG, "registerStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (callback == null) {
            Slog.w(TAG, "unregisterStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public boolean isEnabled() {
        return getState() == BluetoothAdapter.STATE_ON;
    }

    public int getState() {
        if ((Binder.getCallingUid() != Process.SYSTEM_UID) && (!checkIfCallerIsForegroundUser())) {
            Slog.w(TAG, "getState(): report OFF for non-active and non system user");
            return BluetoothAdapter.STATE_OFF;
        }

        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null) {
                return mBluetooth.getState();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "getState()", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
        return BluetoothAdapter.STATE_OFF;
    }

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        private String mPackageName;

        ClientDeathRecipient(String packageName) {
            mPackageName = packageName;
        }

        public void binderDied() {
            if (DBG) {
                Slog.d(TAG, "Binder is dead - unregister " + mPackageName);
            }

            for (Map.Entry<IBinder, ClientDeathRecipient> entry : mBleApps.entrySet()) {
                IBinder token = entry.getKey();
                ClientDeathRecipient deathRec = entry.getValue();
                if (deathRec.equals(this)) {
                    updateBleAppCount(token, false, mPackageName);
                    break;
                }
            }
        }

        public String getPackageName() {
            return mPackageName;
        }
    }

    @Override
    public boolean isBleScanAlwaysAvailable() {
        if (isAirplaneModeOn() && !mEnable) {
            return false;
        }
        try {
            return Settings.Global.getInt(mContentResolver,
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE) != 0;
        } catch (SettingNotFoundException e) {
        }
        return false;
    }

    @Override
    public boolean isHearingAidProfileSupported() {
        return mIsHearingAidProfileSupported;
    }

    @Override
    /** @hide */
    public java.util.List<String> getSystemConfigEnabledProfilesForPackage(String packageName) {
        if (Binder.getCallingUid() != Process.BLUETOOTH_UID) {
            Slog.w(TAG, "getSystemConfigEnabledProfilesForPackage(): not allowed for non-bluetooth");
            return null;
        }

        SystemConfig systemConfig = SystemConfig.getInstance();
        if (systemConfig == null) {
            return null;
        }

        android.util.ArrayMap<String, Boolean> componentEnabledStates =
                systemConfig.getComponentsEnabledStates(packageName);
        if (componentEnabledStates == null) {
            return null;
        }

        ArrayList enabledProfiles = new ArrayList<String>();
        for (Map.Entry<String, Boolean> entry : componentEnabledStates.entrySet()) {
            if (entry.getValue()) {
                enabledProfiles.add(entry.getKey());
            }
        }

        return enabledProfiles;
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED,
                0) != 0;
    }

    // Monitor change of BLE scan only mode settings.
    private void registerForProvisioningStateChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (!isDeviceProvisioned()) {
                    if (DBG) {
                        Slog.d(TAG, "DEVICE_PROVISIONED setting changed, but device is not "
                                + "provisioned");
                    }
                    return;
                }
                if (mHandler.hasMessages(MESSAGE_INIT_FLAGS_CHANGED)) {
                    Slog.i(TAG, "Device provisioned, reactivating pending flag changes");
                    onInitFlagsChanged();
                }
            }
        };

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false,
                contentObserver);
    }

    // Monitor change of BLE scan only mode settings.
    private void registerForBleScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (isBleScanAlwaysAvailable()) {
                    // Nothing to do
                    return;
                }
                // BLE scan is not available.
                disableBleScanMode();
                clearBleApps();
                try {
                    mBluetoothLock.readLock().lock();
                    if (mBluetooth != null) {
                        addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                                mContext.getPackageName(), false);
                        mBluetooth.onBrEdrDown();
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "error when disabling bluetooth", e);
                } finally {
                    mBluetoothLock.readLock().unlock();
                }
            }
        };

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE), false,
                contentObserver);
    }

    // Disable ble scan only mode.
    private void disableBleScanMode() {
        try {
            mBluetoothLock.writeLock().lock();
            if (mBluetooth != null && (mBluetooth.getState() != BluetoothAdapter.STATE_ON)) {
                if (DBG) {
                    Slog.d(TAG, "Reseting the mEnable flag for clean disable");
                }
                mEnable = false;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "getState()", e);
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
    }

    private int updateBleAppCount(IBinder token, boolean enable, String packageName) {
        ClientDeathRecipient r = mBleApps.get(token);
        if (r == null && enable) {
            ClientDeathRecipient deathRec = new ClientDeathRecipient(packageName);
            try {
                token.linkToDeath(deathRec, 0);
            } catch (RemoteException ex) {
                throw new IllegalArgumentException("BLE app (" + packageName + ") already dead!");
            }
            mBleApps.put(token, deathRec);
            if (DBG) {
                Slog.d(TAG, "Registered for death of " + packageName);
            }
        } else if (!enable && r != null) {
            // Unregister death recipient as the app goes away.
            token.unlinkToDeath(r, 0);
            mBleApps.remove(token);
            if (DBG) {
                Slog.d(TAG, "Unregistered for death of " + packageName);
            }
        }
        int appCount = mBleApps.size();
        if (DBG) {
            Slog.d(TAG, appCount + " registered Ble Apps");
        }
        return appCount;
    }

    private boolean checkBluetoothPermissions(String packageName, boolean requireForeground) {
        if (isBluetoothDisallowed()) {
            if (DBG) {
                Slog.d(TAG, "checkBluetoothPermissions: bluetooth disallowed");
            }
            return false;
        }
        // Check if packageName belongs to callingUid
        final int callingUid = Binder.getCallingUid();
        final boolean isCallerSystem = UserHandle.getAppId(callingUid) == Process.SYSTEM_UID;
        if (!isCallerSystem) {
            checkPackage(callingUid, packageName);

            if (requireForeground && !checkIfCallerIsForegroundUser()) {
                Slog.w(TAG, "Not allowed for non-active and non system user");
                return false;
            }

            mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
        }
        return true;
    }

    public boolean enableBle(String packageName, IBinder token) throws RemoteException {
        if (!checkBluetoothPermissions(packageName, false)) {
            if (DBG) {
                Slog.d(TAG, "enableBle(): bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Slog.d(TAG, "enableBle(" + packageName + "):  mBluetooth =" + mBluetooth
                    + " mBinding = " + mBinding + " mState = "
                    + BluetoothAdapter.nameForState(mState));
        }
        updateBleAppCount(token, true, packageName);

        if (mState == BluetoothAdapter.STATE_ON
                || mState == BluetoothAdapter.STATE_BLE_ON
                || mState == BluetoothAdapter.STATE_TURNING_ON
                || mState == BluetoothAdapter.STATE_TURNING_OFF) {
            Log.d(TAG, "enableBLE(): Bluetooth already enabled");
            return true;
        }
        synchronized (mReceiver) {
            // waive WRITE_SECURE_SETTINGS permission check
            sendEnableMsg(false,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        }
        return true;
    }

    public boolean disableBle(String packageName, IBinder token) throws RemoteException {
        if (!checkBluetoothPermissions(packageName, false)) {
            if (DBG) {
                Slog.d(TAG, "disableBLE(): bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Slog.d(TAG, "disableBle(" + packageName + "):  mBluetooth =" + mBluetooth
                    + " mBinding = " + mBinding + " mState = "
                    + BluetoothAdapter.nameForState(mState));
        }

        if (mState == BluetoothAdapter.STATE_OFF) {
            Slog.d(TAG, "disableBLE(): Already disabled");
            return false;
        }
        updateBleAppCount(token, false, packageName);

        if (mState == BluetoothAdapter.STATE_BLE_ON && !isBleAppPresent()) {
            if (mEnable) {
                disableBleScanMode();
            }
            if (!mEnableExternal) {
                addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                        packageName, false);
                sendBrEdrDownCallback();
            }
        }
        return true;
    }

    // Clear all apps using BLE scan only mode.
    private void clearBleApps() {
        mBleApps.clear();
    }

    /** @hide */
    public boolean isBleAppPresent() {
        if (DBG) {
            Slog.d(TAG, "isBleAppPresent() count: " + mBleApps.size());
        }
        return mBleApps.size() > 0;
    }

    /**
     * Call IBluetooth.onLeServiceUp() to continue if Bluetooth should be on,
     * call IBluetooth.onBrEdrDown() to disable if Bluetooth should be off.
     */
    private void continueFromBleOnState() {
        if (DBG) {
            Slog.d(TAG, "continueFromBleOnState()");
        }
        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth == null) {
                Slog.e(TAG, "onBluetoothServiceUp: mBluetooth is null!");
                return;
            }
            if (!mEnableExternal && !isBleAppPresent()) {
                Slog.i(TAG, "Bluetooth was disabled while enabling BLE, disable BLE now");
                mEnable = false;
                mBluetooth.onBrEdrDown();
                return;
            }
            if (isBluetoothPersistedStateOnBluetooth() || !isBleAppPresent()) {
                // This triggers transition to STATE_ON
                mBluetooth.onLeServiceUp();
                persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to call onServiceUp", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    /**
     * Inform BluetoothAdapter instances that BREDR part is down
     * and turn off all service and stack if no LE app needs it
     */
    private void sendBrEdrDownCallback() {
        if (DBG) {
            Slog.d(TAG, "Calling sendBrEdrDownCallback callbacks");
        }

        if (mBluetooth == null) {
            Slog.w(TAG, "Bluetooth handle is null");
            return;
        }

        if (isBleAppPresent()) {
            // Need to stay at BLE ON. Disconnect all Gatt connections
            try {
                mBluetoothGatt.unregAll();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to disconnect all apps.", e);
            }
        } else {
            try {
                mBluetoothLock.readLock().lock();
                if (mBluetooth != null) {
                    mBluetooth.onBrEdrDown();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Call to onBrEdrDown() failed.", e);
            } finally {
                mBluetoothLock.readLock().unlock();
            }
        }

    }

    public boolean enableNoAutoConnect(String packageName) {
        if (!checkBluetoothPermissions(packageName, false)) {
            if (DBG) {
                Slog.d(TAG, "enableNoAutoConnect(): not enabling - bluetooth disallowed");
            }
            return false;
        }

        if (DBG) {
            Slog.d(TAG, "enableNoAutoConnect():  mBluetooth =" + mBluetooth + " mBinding = "
                    + mBinding);
        }

        int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId != Process.NFC_UID) {
            throw new SecurityException("no permission to enable Bluetooth quietly");
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = true;
            mEnableExternal = true;
            sendEnableMsg(true,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        }
        return true;
    }

    public boolean enable(String packageName) throws RemoteException {
        if (!checkBluetoothPermissions(packageName, true)) {
            if (DBG) {
                Slog.d(TAG, "enable(): not enabling - bluetooth disallowed");
            }
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        final boolean callerSystem = UserHandle.getAppId(callingUid) == Process.SYSTEM_UID;
        if (!callerSystem && !isEnabled() && mWirelessConsentRequired
                && startConsentUiIfNeeded(packageName,
                callingUid, BluetoothAdapter.ACTION_REQUEST_ENABLE)) {
            return false;
        }

        if (DBG) {
            Slog.d(TAG, "enable(" + packageName + "):  mBluetooth =" + mBluetooth + " mBinding = "
                    + mBinding + " mState = " + BluetoothAdapter.nameForState(mState));
        }

        synchronized (mReceiver) {
            mQuietEnableExternal = false;
            mEnableExternal = true;
            // waive WRITE_SECURE_SETTINGS permission check
            sendEnableMsg(false,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        }
        if (DBG) {
            Slog.d(TAG, "enable returning");
        }
        return true;
    }

    public boolean disable(String packageName, boolean persist) throws RemoteException {
        if (!checkBluetoothPermissions(packageName, true)) {
            if (DBG) {
                Slog.d(TAG, "disable(): not disabling - bluetooth disallowed");
            }
            return false;
        }

        final int callingUid = Binder.getCallingUid();
        final boolean callerSystem = UserHandle.getAppId(callingUid) == Process.SYSTEM_UID;
        if (!callerSystem && isEnabled() && mWirelessConsentRequired
                && startConsentUiIfNeeded(packageName,
                callingUid, BluetoothAdapter.ACTION_REQUEST_DISABLE)) {
            return false;
        }

        if (DBG) {
            Slog.d(TAG, "disable(): mBluetooth = " + mBluetooth + " mBinding = " + mBinding);
        }

        synchronized (mReceiver) {
            if (!isBluetoothPersistedStateOnAirplane()) {
                if (persist) {
                    persistBluetoothSetting(BLUETOOTH_OFF);
                }
                mEnableExternal = false;
            }
            sendDisableMsg(BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                    packageName);
        }
        return true;
    }

    private boolean startConsentUiIfNeeded(String packageName,
            int callingUid, String intentAction) throws RemoteException {
        if (checkBluetoothPermissionWhenWirelessConsentRequired()) {
            return false;
        }
        try {
            // Validate the package only if we are going to use it
            ApplicationInfo applicationInfo = mContext.getPackageManager()
                    .getApplicationInfoAsUser(packageName,
                            PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.getUserId(callingUid));
            if (applicationInfo.uid != callingUid) {
                throw new SecurityException("Package " + packageName
                        + " not in uid " + callingUid);
            }

            Intent intent = new Intent(intentAction);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // Shouldn't happen
                Slog.e(TAG, "Intent to handle action " + intentAction + " missing");
                return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    /**
     * Check if AppOpsManager is available and the packageName belongs to uid
     *
     * A null package belongs to any uid
     */
    private void checkPackage(int uid, String packageName) {
        if (mAppOps == null) {
            Slog.w(TAG, "checkPackage(): called before system boot up, uid "
                    + uid + ", packageName " + packageName);
            throw new IllegalStateException("System has not boot yet");
        }
        if (packageName == null) {
            Slog.w(TAG, "checkPackage(): called with null packageName from " + uid);
            return;
        }
        try {
            mAppOps.checkPackage(uid, packageName);
        } catch (SecurityException e) {
            Slog.w(TAG, "checkPackage(): " + packageName + " does not belong to uid " + uid);
            throw new SecurityException(e.getMessage());
        }
    }

    /**
     * Check if the caller must still pass permission check or if the caller is exempted
     * from the consent UI via the MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED check.
     *
     * Commands from some callers may be exempted from triggering the consent UI when
     * enabling bluetooth. This exemption is checked via the
     * MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED and allows calls to skip
     * the consent UI where it may otherwise be required.
     *
     * @hide
     */
    private boolean checkBluetoothPermissionWhenWirelessConsentRequired() {
        int result = mContext.checkCallingPermission(
                android.Manifest.permission.MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    public void unbindAndFinish() {
        if (DBG) {
            Slog.d(TAG, "unbindAndFinish(): " + mBluetooth + " mBinding = " + mBinding
                    + " mUnbinding = " + mUnbinding);
        }

        try {
            mBluetoothLock.writeLock().lock();
            if (mUnbinding) {
                return;
            }
            mUnbinding = true;
            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE);
            if (mBluetooth != null) {
                //Unregister callback object
                try {
                    mBluetooth.unregisterCallback(mBluetoothCallback);
                } catch (RemoteException re) {
                    Slog.e(TAG, "Unable to unregister BluetoothCallback", re);
                }
                mBluetoothBinder = null;
                mBluetooth = null;
                mContext.unbindService(mConnection);
                mUnbinding = false;
                mBinding = false;
            } else {
                mUnbinding = false;
            }
            mBluetoothGatt = null;
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
    }

    public IBluetoothGatt getBluetoothGatt() {
        // sync protection
        return mBluetoothGatt;
    }

    @Override
    public boolean bindBluetoothProfileService(int bluetoothProfile,
            IBluetoothProfileServiceConnection proxy) {
        if (mState != BluetoothAdapter.STATE_ON) {
            if (DBG) {
                Slog.d(TAG, "Trying to bind to profile: " + bluetoothProfile
                        + ", while Bluetooth was disabled");
            }
            return false;
        }
        synchronized (mProfileServices) {
            ProfileServiceConnections psc = mProfileServices.get(new Integer(bluetoothProfile));
            if (psc == null) {
                if (DBG) {
                    Slog.d(TAG, "Creating new ProfileServiceConnections object for" + " profile: "
                            + bluetoothProfile);
                }

                if (bluetoothProfile != BluetoothProfile.HEADSET) {
                    return false;
                }

                Intent intent = new Intent(IBluetoothHeadset.class.getName());
                psc = new ProfileServiceConnections(intent);
                if (!psc.bindService()) {
                    return false;
                }

                mProfileServices.put(new Integer(bluetoothProfile), psc);
            }
        }

        // Introducing a delay to give the client app time to prepare
        Message addProxyMsg = mHandler.obtainMessage(MESSAGE_ADD_PROXY_DELAYED);
        addProxyMsg.arg1 = bluetoothProfile;
        addProxyMsg.obj = proxy;
        mHandler.sendMessageDelayed(addProxyMsg, ADD_PROXY_DELAY_MS);
        return true;
    }

    @Override
    public void unbindBluetoothProfileService(int bluetoothProfile,
            IBluetoothProfileServiceConnection proxy) {
        synchronized (mProfileServices) {
            Integer profile = new Integer(bluetoothProfile);
            ProfileServiceConnections psc = mProfileServices.get(profile);
            if (psc == null) {
                return;
            }
            psc.removeProxy(proxy);
            if (psc.isEmpty()) {
                // All prxoies are disconnected, unbind with the service.
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                mProfileServices.remove(profile);
            }
        }
    }

    private void unbindAllBluetoothProfileServices() {
        synchronized (mProfileServices) {
            for (Integer i : mProfileServices.keySet()) {
                ProfileServiceConnections psc = mProfileServices.get(i);
                try {
                    mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                psc.removeAllProxies();
            }
            mProfileServices.clear();
        }
    }

    /**
     * Send enable message and set adapter name and address. Called when the boot phase becomes
     * PHASE_SYSTEM_SERVICES_READY.
     */
    public void handleOnBootPhase() {
        if (DBG) {
            Slog.d(TAG, "Bluetooth boot completed");
        }
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        userManagerInternal.addUserRestrictionsListener(mUserRestrictionsListener);
        final boolean isBluetoothDisallowed = isBluetoothDisallowed();
        if (isBluetoothDisallowed) {
            return;
        }
        final boolean isSafeMode = mContext.getPackageManager().isSafeMode();
        if (mEnableExternal && isBluetoothPersistedStateOnBluetooth() && !isSafeMode) {
            if (DBG) {
                Slog.d(TAG, "Auto-enabling Bluetooth.");
            }
            sendEnableMsg(mQuietEnableExternal,
                    BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT,
                    mContext.getPackageName());
        } else if (!isNameAndAddressSet()) {
            if (DBG) {
                Slog.d(TAG, "Getting adapter name and address");
            }
            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
            mHandler.sendMessage(getMsg);
        }

        mBluetoothModeChangeHelper = new BluetoothModeChangeHelper(mContext);
        if (mBluetoothAirplaneModeListener != null) {
            mBluetoothAirplaneModeListener.start(mBluetoothModeChangeHelper);
        }
        registerForProvisioningStateChange();
        mBluetoothDeviceConfigListener = new BluetoothDeviceConfigListener(this, DBG);
    }

    /**
     * Called when switching to a different foreground user.
     */
    public void handleOnSwitchUser(int userHandle) {
        if (DBG) {
            Slog.d(TAG, "User " + userHandle + " switched");
        }
        mHandler.obtainMessage(MESSAGE_USER_SWITCHED, userHandle, 0).sendToTarget();
    }

    /**
     * Called when user is unlocked.
     */
    public void handleOnUnlockUser(int userHandle) {
        if (DBG) {
            Slog.d(TAG, "User " + userHandle + " unlocked");
        }
        mHandler.obtainMessage(MESSAGE_USER_UNLOCKED, userHandle, 0).sendToTarget();
    }

    /**
     * This class manages the clients connected to a given ProfileService
     * and maintains the connection with that service.
     */
    private final class ProfileServiceConnections
            implements ServiceConnection, IBinder.DeathRecipient {
        final RemoteCallbackList<IBluetoothProfileServiceConnection> mProxies =
                new RemoteCallbackList<IBluetoothProfileServiceConnection>();
        IBinder mService;
        ComponentName mClassName;
        Intent mIntent;
        boolean mInvokingProxyCallbacks = false;

        ProfileServiceConnections(Intent intent) {
            mService = null;
            mClassName = null;
            mIntent = intent;
        }

        private boolean bindService() {
            int state = BluetoothAdapter.STATE_OFF;
            try {
                mBluetoothLock.readLock().lock();
                if (mBluetooth != null) {
                    state = mBluetooth.getState();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to call getState", e);
                return false;
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            if (state != BluetoothAdapter.STATE_ON) {
                if (DBG) {
                    Slog.d(TAG, "Unable to bindService while Bluetooth is disabled");
                }
                return false;
            }

            if (mIntent != null && mService == null && doBind(mIntent, this, 0,
                    UserHandle.CURRENT_OR_SELF)) {
                Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
                msg.obj = this;
                mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
                return true;
            }
            Slog.w(TAG, "Unable to bind with intent: " + mIntent);
            return false;
        }

        private void addProxy(IBluetoothProfileServiceConnection proxy) {
            mProxies.register(proxy);
            if (mService != null) {
                try {
                    proxy.onServiceConnected(mClassName, mService);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to connect to proxy", e);
                }
            } else {
                if (!mHandler.hasMessages(MESSAGE_BIND_PROFILE_SERVICE, this)) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
                    msg.obj = this;
                    mHandler.sendMessage(msg);
                }
            }
        }

        private void removeProxy(IBluetoothProfileServiceConnection proxy) {
            if (proxy != null) {
                if (mProxies.unregister(proxy)) {
                    try {
                        proxy.onServiceDisconnected(mClassName);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to disconnect proxy", e);
                    }
                }
            } else {
                Slog.w(TAG, "Trying to remove a null proxy");
            }
        }

        private void removeAllProxies() {
            onServiceDisconnected(mClassName);
            mProxies.kill();
        }

        private boolean isEmpty() {
            return mProxies.getRegisteredCallbackCount() == 0;
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // remove timeout message
            mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE, this);
            mService = service;
            mClassName = className;
            try {
                mService.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to linkToDeath", e);
            }

            if (mInvokingProxyCallbacks) {
                Slog.e(TAG, "Proxy callbacks already in progress.");
                return;
            }
            mInvokingProxyCallbacks = true;

            final int n = mProxies.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    try {
                        mProxies.getBroadcastItem(i).onServiceConnected(className, service);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to connect to proxy", e);
                    }
                }
            } finally {
                mProxies.finishBroadcast();
                mInvokingProxyCallbacks = false;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (mService == null) {
                return;
            }
            try {
                mService.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "error unlinking to death", e);
            }
            mService = null;
            mClassName = null;

            if (mInvokingProxyCallbacks) {
                Slog.e(TAG, "Proxy callbacks already in progress.");
                return;
            }
            mInvokingProxyCallbacks = true;

            final int n = mProxies.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    try {
                        mProxies.getBroadcastItem(i).onServiceDisconnected(className);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to disconnect from proxy", e);
                    }
                }
            } finally {
                mProxies.finishBroadcast();
                mInvokingProxyCallbacks = false;
            }
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Slog.w(TAG, "Profile service for profile: " + mClassName + " died.");
            }
            onServiceDisconnected(mClassName);
            // Trigger rebind
            Message msg = mHandler.obtainMessage(MESSAGE_BIND_PROFILE_SERVICE);
            msg.obj = this;
            mHandler.sendMessageDelayed(msg, TIMEOUT_BIND_MS);
        }
    }

    private void sendBluetoothStateCallback(boolean isUp) {
        try {
            int n = mStateChangeCallbacks.beginBroadcast();
            if (DBG) {
                Slog.d(TAG, "Broadcasting onBluetoothStateChange(" + isUp + ") to " + n
                        + " receivers.");
            }
            for (int i = 0; i < n; i++) {
                try {
                    mStateChangeCallbacks.getBroadcastItem(i).onBluetoothStateChange(isUp);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i, e);
                }
            }
        } finally {
            mStateChangeCallbacks.finishBroadcast();
        }
    }

    /**
     * Inform BluetoothAdapter instances that Adapter service is up
     */
    private void sendBluetoothServiceUpCallback() {
        synchronized (mCallbacks) {
            try {
                int n = mCallbacks.beginBroadcast();
                Slog.d(TAG, "Broadcasting onBluetoothServiceUp() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onBluetoothServiceUp(mBluetooth);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }
        }
    }

    /**
     * Inform BluetoothAdapter instances that Adapter service is down
     */
    private void sendBluetoothServiceDownCallback() {
        synchronized (mCallbacks) {
            try {
                int n = mCallbacks.beginBroadcast();
                Slog.d(TAG, "Broadcasting onBluetoothServiceDown() to " + n + " receivers.");
                for (int i = 0; i < n; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onBluetoothServiceDown();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to call onBluetoothServiceDown() on callback #" + i, e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }
        }
    }

    public String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if ((Binder.getCallingUid() != Process.SYSTEM_UID) && (!checkIfCallerIsForegroundUser())) {
            Slog.w(TAG, "getAddress(): not allowed for non-active and non system user");
            return null;
        }

        if (mContext.checkCallingOrSelfPermission(Manifest.permission.LOCAL_MAC_ADDRESS)
                != PackageManager.PERMISSION_GRANTED) {
            return BluetoothAdapter.DEFAULT_MAC_ADDRESS;
        }

        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null) {
                return mBluetooth.getAddress();
            }
        } catch (RemoteException e) {
            Slog.e(TAG,
                    "getAddress(): Unable to retrieve address remotely. Returning cached address",
                    e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        // mAddress is accessed from outside.
        // It is alright without a lock. Here, bluetooth is off, no other thread is
        // changing mAddress
        return mAddress;
    }

    public String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if ((Binder.getCallingUid() != Process.SYSTEM_UID) && (!checkIfCallerIsForegroundUser())) {
            Slog.w(TAG, "getName(): not allowed for non-active and non system user");
            return null;
        }

        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null) {
                return mBluetooth.getName();
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "getName(): Unable to retrieve name remotely. Returning cached name", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        // mName is accessed from outside.
        // It alright without a lock. Here, bluetooth is off, no other thread is
        // changing mName
        return mName;
    }

    private class BluetoothServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            if (DBG) {
                Slog.d(TAG, "BluetoothServiceConnection: " + name);
            }
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = SERVICE_IBLUETOOTH;
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = SERVICE_IBLUETOOTHGATT;
            } else {
                Slog.e(TAG, "Unknown service connected: " + name);
                return;
            }
            msg.obj = service;
            mHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName componentName) {
            // Called if we unexpectedly disconnect.
            String name = componentName.getClassName();
            if (DBG) {
                Slog.d(TAG, "BluetoothServiceConnection, disconnected: " + name);
            }
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = SERVICE_IBLUETOOTH;
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = SERVICE_IBLUETOOTHGATT;
            } else {
                Slog.e(TAG, "Unknown service disconnected: " + name);
                return;
            }
            mHandler.sendMessage(msg);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    private class BluetoothHandler extends Handler {
        boolean mGetNameAddressOnly = false;

        BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS:
                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                    }
                    try {
                        mBluetoothLock.writeLock().lock();
                        if ((mBluetooth == null) && (!mBinding)) {
                            if (DBG) {
                                Slog.d(TAG, "Binding to service to get name and address");
                            }
                            mGetNameAddressOnly = true;
                            Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg, TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!doBind(i, mConnection,
                                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                                    UserHandle.CURRENT)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                            } else {
                                mBinding = true;
                            }
                        } else if (mBluetooth != null) {
                            try {
                                storeNameAndAddress(mBluetooth.getName(), mBluetooth.getAddress());
                            } catch (RemoteException re) {
                                Slog.e(TAG, "Unable to grab names", re);
                            }
                            if (mGetNameAddressOnly && !mEnable) {
                                unbindAndFinish();
                            }
                            mGetNameAddressOnly = false;
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }
                    break;

                case MESSAGE_ENABLE:
                    int quietEnable = msg.arg1;
                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        // We are handling enable or disable right now, wait for it.
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_ENABLE,
                                quietEnable, 0), ENABLE_DISABLE_DELAY_MS);
                        break;
                    }

                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_ENABLE(" + quietEnable + "): mBluetooth = "
                                + mBluetooth);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mEnable = true;

                    // Use service interface to get the exact state
                    try {
                        mBluetoothLock.readLock().lock();
                        if (mBluetooth != null) {
                            int state = mBluetooth.getState();
                            if (state == BluetoothAdapter.STATE_BLE_ON) {
                                Slog.w(TAG, "BT Enable in BLE_ON State, going to ON");
                                mBluetooth.onLeServiceUp();
                                persistBluetoothSetting(BLUETOOTH_ON_BLUETOOTH);
                                break;
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "", e);
                    } finally {
                        mBluetoothLock.readLock().unlock();
                    }

                    mQuietEnable = (quietEnable == 1);
                    if (mBluetooth == null) {
                        handleEnable(mQuietEnable);
                    } else {
                        //
                        // We need to wait until transitioned to STATE_OFF and
                        // the previous Bluetooth process has exited. The
                        // waiting period has three components:
                        // (a) Wait until the local state is STATE_OFF. This
                        //     is accomplished by sending delay a message
                        //     MESSAGE_HANDLE_ENABLE_DELAYED
                        // (b) Wait until the STATE_OFF state is updated to
                        //     all components.
                        // (c) Wait until the Bluetooth process exits, and
                        //     ActivityManager detects it.
                        // The waiting for (b) and (c) is accomplished by
                        // delaying the MESSAGE_RESTART_BLUETOOTH_SERVICE
                        // message. The delay time is backed off if Bluetooth
                        // continuously failed to turn on itself.
                        //
                        mWaitForEnableRetry = 0;
                        Message enableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_ENABLE_DELAYED);
                        mHandler.sendMessageDelayed(enableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                    }
                    break;

                case MESSAGE_DISABLE:
                    if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED) || mBinding
                            || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
                        // We are handling enable or disable right now, wait for it.
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_DISABLE),
                                ENABLE_DISABLE_DELAY_MS);
                        break;
                    }

                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_DISABLE: mBluetooth = " + mBluetooth
                                + ", mBinding = " + mBinding);
                    }
                    mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);

                    if (mEnable && mBluetooth != null) {
                        mWaitForDisableRetry = 0;
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 0, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                    } else {
                        mEnable = false;
                        handleDisable();
                    }
                    break;

                case MESSAGE_HANDLE_ENABLE_DELAYED: {
                    // The Bluetooth is turning off, wait for STATE_OFF
                    if (mState != BluetoothAdapter.STATE_OFF) {
                        if (mWaitForEnableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                            mWaitForEnableRetry++;
                            Message enableDelayedMsg =
                                    mHandler.obtainMessage(MESSAGE_HANDLE_ENABLE_DELAYED);
                            mHandler.sendMessageDelayed(enableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                            break;
                        } else {
                            Slog.e(TAG, "Wait for STATE_OFF timeout");
                        }
                    }
                    // Either state is changed to STATE_OFF or reaches the maximum retry, we
                    // should move forward to the next step.
                    mWaitForEnableRetry = 0;
                    Message restartMsg =
                            mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                    mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                    Slog.d(TAG, "Handle enable is finished");
                    break;
                }

                case MESSAGE_HANDLE_DISABLE_DELAYED: {
                    boolean disabling = (msg.arg1 == 1);
                    Slog.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: disabling:" + disabling);
                    if (!disabling) {
                        // The Bluetooth is turning on, wait for STATE_ON
                        if (mState != BluetoothAdapter.STATE_ON) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg = mHandler.obtainMessage(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, 0, 0);
                                mHandler.sendMessageDelayed(disableDelayedMsg,
                                        ENABLE_DISABLE_DELAY_MS);
                                break;
                            } else {
                                Slog.e(TAG, "Wait for STATE_ON timeout");
                            }
                        }
                        // Either state is changed to STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        mWaitForDisableRetry = 0;
                        mEnable = false;
                        handleDisable();
                        // Wait for state exiting STATE_ON
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                    } else {
                        // The Bluetooth is turning off, wait for exiting STATE_ON
                        if (mState == BluetoothAdapter.STATE_ON) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg = mHandler.obtainMessage(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                                mHandler.sendMessageDelayed(disableDelayedMsg,
                                        ENABLE_DISABLE_DELAY_MS);
                                break;
                            } else {
                                Slog.e(TAG, "Wait for exiting STATE_ON timeout");
                            }
                        }
                        // Either state is exited from STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        Slog.d(TAG, "Handle disable is finished");
                    }
                    break;
                }

                case MESSAGE_RESTORE_USER_SETTING:
                    if ((msg.arg1 == RESTORE_SETTING_TO_OFF) && mEnable) {
                        if (DBG) {
                            Slog.d(TAG, "Restore Bluetooth state to disabled");
                        }
                        persistBluetoothSetting(BLUETOOTH_OFF);
                        mEnableExternal = false;
                        sendDisableMsg(
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                mContext.getPackageName());
                    } else if ((msg.arg1 == RESTORE_SETTING_TO_ON) && !mEnable) {
                        if (DBG) {
                            Slog.d(TAG, "Restore Bluetooth state to enabled");
                        }
                        mQuietEnableExternal = false;
                        mEnableExternal = true;
                        // waive WRITE_SECURE_SETTINGS permission check
                        sendEnableMsg(false,
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING,
                                mContext.getPackageName());
                    }
                    break;
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK: {
                    IBluetoothStateChangeCallback callback =
                            (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.register(callback);
                    break;
                }
                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK: {
                    IBluetoothStateChangeCallback callback =
                            (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.unregister(callback);
                    break;
                }
                case MESSAGE_ADD_PROXY_DELAYED: {
                    ProfileServiceConnections psc = mProfileServices.get(msg.arg1);
                    if (psc == null) {
                        break;
                    }
                    IBluetoothProfileServiceConnection proxy =
                            (IBluetoothProfileServiceConnection) msg.obj;
                    psc.addProxy(proxy);
                    break;
                }
                case MESSAGE_BIND_PROFILE_SERVICE: {
                    ProfileServiceConnections psc = (ProfileServiceConnections) msg.obj;
                    removeMessages(MESSAGE_BIND_PROFILE_SERVICE, msg.obj);
                    if (psc == null) {
                        break;
                    }
                    psc.bindService();
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED: {
                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: " + msg.arg1);
                    }

                    IBinder service = (IBinder) msg.obj;
                    try {
                        mBluetoothLock.writeLock().lock();
                        if (msg.arg1 == SERVICE_IBLUETOOTHGATT) {
                            mBluetoothGatt =
                                    IBluetoothGatt.Stub.asInterface(Binder.allowBlocking(service));
                            continueFromBleOnState();
                            break;
                        } // else must be SERVICE_IBLUETOOTH

                        //Remove timeout
                        mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                        mBinding = false;
                        mBluetoothBinder = service;
                        mBluetooth = IBluetooth.Stub.asInterface(Binder.allowBlocking(service));

                        if (!isNameAndAddressSet()) {
                            Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            mHandler.sendMessage(getMsg);
                            if (mGetNameAddressOnly) {
                                return;
                            }
                        }

                        //Register callback object
                        try {
                            mBluetooth.registerCallback(mBluetoothCallback);
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Unable to register BluetoothCallback", re);
                        }
                        //Inform BluetoothAdapter instances that service is up
                        sendBluetoothServiceUpCallback();

                        //Do enable request
                        try {
                            if (!mBluetooth.enable(mQuietEnable)) {
                                Slog.e(TAG, "IBluetooth.enable() returned false");
                            }
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Unable to call enable()", e);
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }

                    if (!mEnable) {
                        waitForState(Set.of(BluetoothAdapter.STATE_ON));
                        handleDisable();
                        waitForState(Set.of(BluetoothAdapter.STATE_OFF,
                                BluetoothAdapter.STATE_TURNING_ON,
                                BluetoothAdapter.STATE_TURNING_OFF,
                                BluetoothAdapter.STATE_BLE_TURNING_ON,
                                BluetoothAdapter.STATE_BLE_ON,
                                BluetoothAdapter.STATE_BLE_TURNING_OFF));
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_STATE_CHANGE: {
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    if (DBG) {
                        Slog.d(TAG,
                                "MESSAGE_BLUETOOTH_STATE_CHANGE: " + BluetoothAdapter.nameForState(
                                        prevState) + " > " + BluetoothAdapter.nameForState(
                                        newState));
                    }
                    mState = newState;
                    bluetoothStateChangeHandler(prevState, newState);
                    // handle error state transition case from TURNING_ON to OFF
                    // unbind and rebind bluetooth service and enable bluetooth
                    if ((prevState == BluetoothAdapter.STATE_BLE_TURNING_ON) && (newState
                            == BluetoothAdapter.STATE_OFF) && (mBluetooth != null) && mEnable) {
                        recoverBluetoothServiceFromError(false);
                    }
                    if ((prevState == BluetoothAdapter.STATE_TURNING_ON) && (newState
                            == BluetoothAdapter.STATE_BLE_ON) && (mBluetooth != null) && mEnable) {
                        recoverBluetoothServiceFromError(true);
                    }
                    // If we tried to enable BT while BT was in the process of shutting down,
                    // wait for the BT process to fully tear down and then force a restart
                    // here.  This is a bit of a hack (b/29363429).
                    if ((prevState == BluetoothAdapter.STATE_BLE_TURNING_OFF) && (newState
                            == BluetoothAdapter.STATE_OFF)) {
                        if (mEnable) {
                            Slog.d(TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                            waitForState(Set.of(BluetoothAdapter.STATE_OFF));
                            Message restartMsg =
                                    mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                            mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                        }
                    }
                    if (newState == BluetoothAdapter.STATE_ON
                            || newState == BluetoothAdapter.STATE_BLE_ON) {
                        // bluetooth is working, reset the counter
                        if (mErrorRecoveryRetryCounter != 0) {
                            Slog.w(TAG, "bluetooth is recovered from error");
                            mErrorRecoveryRetryCounter = 0;
                        }
                    }
                    break;
                }
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED: {
                    Slog.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED(" + msg.arg1 + ")");
                    try {
                        mBluetoothLock.writeLock().lock();
                        if (msg.arg1 == SERVICE_IBLUETOOTH) {
                            // if service is unbinded already, do nothing and return
                            if (mBluetooth == null) {
                                break;
                            }
                            mBluetooth = null;
                        } else if (msg.arg1 == SERVICE_IBLUETOOTHGATT) {
                            mBluetoothGatt = null;
                            break;
                        } else {
                            Slog.e(TAG, "Unknown argument for service disconnect!");
                            break;
                        }
                    } finally {
                        mBluetoothLock.writeLock().unlock();
                    }

                    // log the unexpected crash
                    addCrashLog();
                    addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH,
                            mContext.getPackageName(), false);
                    if (mEnable) {
                        mEnable = false;
                        // Send a Bluetooth Restart message
                        Message restartMsg =
                                mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
                        mHandler.sendMessageDelayed(restartMsg, getServiceRestartMs());
                    }

                    sendBluetoothServiceDownCallback();

                    // Send BT state broadcast to update
                    // the BT icon correctly
                    if ((mState == BluetoothAdapter.STATE_TURNING_ON) || (mState
                            == BluetoothAdapter.STATE_ON)) {
                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                                BluetoothAdapter.STATE_TURNING_OFF);
                        mState = BluetoothAdapter.STATE_TURNING_OFF;
                    }
                    if (mState == BluetoothAdapter.STATE_TURNING_OFF) {
                        bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                                BluetoothAdapter.STATE_OFF);
                    }

                    mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
                    mState = BluetoothAdapter.STATE_OFF;
                    break;
                }
                case MESSAGE_RESTART_BLUETOOTH_SERVICE: {
                    mErrorRecoveryRetryCounter++;
                    Slog.d(TAG, "MESSAGE_RESTART_BLUETOOTH_SERVICE: retry count="
                            + mErrorRecoveryRetryCounter);
                    if (mErrorRecoveryRetryCounter < MAX_ERROR_RESTART_RETRIES) {
                        /* Enable without persisting the setting as
                         it doesnt change when IBluetooth
                         service restarts */
                        mEnable = true;
                        addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED,
                                mContext.getPackageName(), true);
                        handleEnable(mQuietEnable);
                    } else {
                        Slog.e(TAG, "Reach maximum retry to restart Bluetooth!");
                    }
                    break;
                }
                case MESSAGE_TIMEOUT_BIND: {
                    Slog.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    mBluetoothLock.writeLock().lock();
                    mBinding = false;
                    mBluetoothLock.writeLock().unlock();
                    break;
                }
                case MESSAGE_TIMEOUT_UNBIND: {
                    Slog.e(TAG, "MESSAGE_TIMEOUT_UNBIND");
                    mBluetoothLock.writeLock().lock();
                    mUnbinding = false;
                    mBluetoothLock.writeLock().unlock();
                    break;
                }

                case MESSAGE_USER_SWITCHED: {
                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_USER_SWITCHED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    /* disable and enable BT when detect a user switch */
                    if (mBluetooth != null && isEnabled()) {
                        restartForReason(BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH);
                    } else if (mBinding || mBluetooth != null) {
                        Message userMsg = mHandler.obtainMessage(MESSAGE_USER_SWITCHED);
                        userMsg.arg2 = 1 + msg.arg2;
                        // if user is switched when service is binding retry after a delay
                        mHandler.sendMessageDelayed(userMsg, USER_SWITCHED_TIME_MS);
                        if (DBG) {
                            Slog.d(TAG, "Retry MESSAGE_USER_SWITCHED " + userMsg.arg2);
                        }
                    }
                    break;
                }
                case MESSAGE_USER_UNLOCKED: {
                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_USER_UNLOCKED");
                    }
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    if (mEnable && !mBinding && (mBluetooth == null)) {
                        // We should be connected, but we gave up for some
                        // reason; maybe the Bluetooth service wasn't encryption
                        // aware, so try binding again.
                        if (DBG) {
                            Slog.d(TAG, "Enabled but not bound; retrying after unlock");
                        }
                        handleEnable(mQuietEnable);
                    }
                    break;
                }
                case MESSAGE_INIT_FLAGS_CHANGED: {
                    if (DBG) {
                        Slog.d(TAG, "MESSAGE_INIT_FLAGS_CHANGED");
                    }
                    mHandler.removeMessages(MESSAGE_INIT_FLAGS_CHANGED);
                    if (mBluetoothModeChangeHelper.isA2dpOrHearingAidConnected()) {
                        Slog.i(TAG, "Delaying MESSAGE_INIT_FLAGS_CHANGED by "
                                + DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS
                                + " ms due to existing connections");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_INIT_FLAGS_CHANGED,
                                DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS);
                        break;
                    }
                    if (!isDeviceProvisioned()) {
                        Slog.i(TAG, "Delaying MESSAGE_INIT_FLAGS_CHANGED by "
                                + DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS
                                +  "ms because device is not provisioned");
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_INIT_FLAGS_CHANGED,
                                DELAY_FOR_RETRY_INIT_FLAG_CHECK_MS);
                        break;
                    }
                    if (mBluetooth != null && isEnabled()) {
                        Slog.i(TAG, "Restarting Bluetooth due to init flag change");
                        restartForReason(
                                BluetoothProtoEnums.ENABLE_DISABLE_REASON_INIT_FLAGS_CHANGED);
                    }
                    break;
                }
            }
        }

        private void restartForReason(int reason) {
            try {
                mBluetoothLock.readLock().lock();
                if (mBluetooth != null) {
                    mBluetooth.unregisterCallback(mBluetoothCallback);
                }
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to unregister", re);
            } finally {
                mBluetoothLock.readLock().unlock();
            }

            if (mState == BluetoothAdapter.STATE_TURNING_OFF) {
                // MESSAGE_USER_SWITCHED happened right after MESSAGE_ENABLE
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_OFF);
                mState = BluetoothAdapter.STATE_OFF;
            }
            if (mState == BluetoothAdapter.STATE_OFF) {
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_TURNING_ON);
                mState = BluetoothAdapter.STATE_TURNING_ON;
            }

            waitForState(Set.of(BluetoothAdapter.STATE_ON));

            if (mState == BluetoothAdapter.STATE_TURNING_ON) {
                bluetoothStateChangeHandler(mState, BluetoothAdapter.STATE_ON);
            }

            unbindAllBluetoothProfileServices();
            // disable
            addActiveLog(reason, mContext.getPackageName(), false);
            handleDisable();
            // Pbap service need receive STATE_TURNING_OFF intent to close
            bluetoothStateChangeHandler(BluetoothAdapter.STATE_ON,
                    BluetoothAdapter.STATE_TURNING_OFF);

            boolean didDisableTimeout =
                    !waitForState(Set.of(BluetoothAdapter.STATE_OFF));

            bluetoothStateChangeHandler(BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF);
            sendBluetoothServiceDownCallback();

            try {
                mBluetoothLock.writeLock().lock();
                if (mBluetooth != null) {
                    mBluetooth = null;
                    // Unbind
                    mContext.unbindService(mConnection);
                }
                mBluetoothGatt = null;
            } finally {
                mBluetoothLock.writeLock().unlock();
            }

            //
            // If disabling Bluetooth times out, wait for an
            // additional amount of time to ensure the process is
            // shut down completely before attempting to restart.
            //
            if (didDisableTimeout) {
                SystemClock.sleep(3000);
            } else {
                SystemClock.sleep(100);
            }

            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            mState = BluetoothAdapter.STATE_OFF;
            // enable
            addActiveLog(reason, mContext.getPackageName(), true);
            // mEnable flag could have been reset on disableBLE. Reenable it.
            mEnable = true;
            handleEnable(mQuietEnable);
        }
    }

    private void handleEnable(boolean quietMode) {
        mQuietEnable = quietMode;

        try {
            mBluetoothLock.writeLock().lock();
            if ((mBluetooth == null) && (!mBinding)) {
                Slog.d(TAG, "binding Bluetooth service");
                //Start bind timeout and bind
                Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                mHandler.sendMessageDelayed(timeoutMsg, TIMEOUT_BIND_MS);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!doBind(i, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                        UserHandle.CURRENT)) {
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                } else {
                    mBinding = true;
                }
            } else if (mBluetooth != null) {
                //Enable bluetooth
                try {
                    if (!mBluetooth.enable(mQuietEnable)) {
                        Slog.e(TAG, "IBluetooth.enable() returned false");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call enable()", e);
                }
            }
        } finally {
            mBluetoothLock.writeLock().unlock();
        }
    }

    boolean doBind(Intent intent, ServiceConnection conn, int flags, UserHandle user) {
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindServiceAsUser(intent, conn, flags, user)) {
            Slog.e(TAG, "Fail to bind to: " + intent);
            return false;
        }
        return true;
    }

    private void handleDisable() {
        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null) {
                if (DBG) {
                    Slog.d(TAG, "Sending off request.");
                }
                if (!mBluetooth.disable()) {
                    Slog.e(TAG, "IBluetooth.disable() returned false");
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to call disable()", e);
        } finally {
            mBluetoothLock.readLock().unlock();
        }
    }

    private boolean checkIfCallerIsForegroundUser() {
        int foregroundUser;
        int callingUser = UserHandle.getCallingUserId();
        int callingUid = Binder.getCallingUid();
        long callingIdentity = Binder.clearCallingIdentity();
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        UserInfo ui = um.getProfileParent(callingUser);
        int parentUser = (ui != null) ? ui.id : UserHandle.USER_NULL;
        int callingAppId = UserHandle.getAppId(callingUid);
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = (callingUser == foregroundUser) || parentUser == foregroundUser
                    || callingAppId == Process.NFC_UID || callingAppId == mSystemUiUid;
            if (DBG && !valid) {
                Slog.d(TAG, "checkIfCallerIsForegroundUser: valid=" + valid + " callingUser="
                        + callingUser + " parentUser=" + parentUser + " foregroundUser="
                        + foregroundUser);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private void sendBleStateChanged(int prevState, int newState) {
        if (DBG) {
            Slog.d(TAG,
                    "Sending BLE State Change: " + BluetoothAdapter.nameForState(prevState) + " > "
                            + BluetoothAdapter.nameForState(newState));
        }
        // Send broadcast message to everyone else
        Intent intent = new Intent(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, BLUETOOTH_PERM);
    }

    private boolean isBleState(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_BLE_ON:
            case BluetoothAdapter.STATE_BLE_TURNING_ON:
            case BluetoothAdapter.STATE_BLE_TURNING_OFF:
                return true;
        }
        return false;
    }

    private void bluetoothStateChangeHandler(int prevState, int newState) {
        boolean isStandardBroadcast = true;
        if (prevState == newState) { // No change. Nothing to do.
            return;
        }
        // Notify all proxy objects first of adapter state change
        if (newState == BluetoothAdapter.STATE_BLE_ON || newState == BluetoothAdapter.STATE_OFF) {
            boolean intermediate_off = (prevState == BluetoothAdapter.STATE_TURNING_OFF
                    && newState == BluetoothAdapter.STATE_BLE_ON);

            if (newState == BluetoothAdapter.STATE_OFF) {
                // If Bluetooth is off, send service down event to proxy objects, and unbind
                if (DBG) {
                    Slog.d(TAG, "Bluetooth is complete send Service Down");
                }
                sendBluetoothServiceDownCallback();
                unbindAndFinish();
                sendBleStateChanged(prevState, newState);

                /* Currently, the OFF intent is broadcasted externally only when we transition
                 * from TURNING_OFF to BLE_ON state. So if the previous state is a BLE state,
                 * we are guaranteed that the OFF intent has been broadcasted earlier and we
                 * can safely skip it.
                 * Conversely, if the previous state is not a BLE state, it indicates that some
                 * sort of crash has occurred, moving us directly to STATE_OFF without ever
                 * passing through BLE_ON. We should broadcast the OFF intent in this case. */
                isStandardBroadcast = !isBleState(prevState);

            } else if (!intermediate_off) {
                // connect to GattService
                if (DBG) {
                    Slog.d(TAG, "Bluetooth is in LE only mode");
                }
                if (mBluetoothGatt != null || !mContext.getPackageManager()
                            .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                    continueFromBleOnState();
                } else {
                    if (DBG) {
                        Slog.d(TAG, "Binding Bluetooth GATT service");
                    }
                    Intent i = new Intent(IBluetoothGatt.class.getName());
                    doBind(i, mConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                            UserHandle.CURRENT);
                }
                sendBleStateChanged(prevState, newState);
                //Don't broadcase this as std intent
                isStandardBroadcast = false;

            } else if (intermediate_off) {
                if (DBG) {
                    Slog.d(TAG, "Intermediate off, back to LE only mode");
                }
                // For LE only mode, broadcast as is
                sendBleStateChanged(prevState, newState);
                sendBluetoothStateCallback(false); // BT is OFF for general users
                // Broadcast as STATE_OFF
                newState = BluetoothAdapter.STATE_OFF;
                sendBrEdrDownCallback();
            }
        } else if (newState == BluetoothAdapter.STATE_ON) {
            boolean isUp = (newState == BluetoothAdapter.STATE_ON);
            sendBluetoothStateCallback(isUp);
            sendBleStateChanged(prevState, newState);

        } else if (newState == BluetoothAdapter.STATE_BLE_TURNING_ON
                || newState == BluetoothAdapter.STATE_BLE_TURNING_OFF) {
            sendBleStateChanged(prevState, newState);
            isStandardBroadcast = false;

        } else if (newState == BluetoothAdapter.STATE_TURNING_ON
                || newState == BluetoothAdapter.STATE_TURNING_OFF) {
            sendBleStateChanged(prevState, newState);
        }

        if (isStandardBroadcast) {
            if (prevState == BluetoothAdapter.STATE_BLE_ON) {
                // Show prevState of BLE_ON as OFF to standard users
                prevState = BluetoothAdapter.STATE_OFF;
            }
            if (DBG) {
                Slog.d(TAG,
                        "Sending State Change: " + BluetoothAdapter.nameForState(prevState) + " > "
                                + BluetoothAdapter.nameForState(newState));
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL, BLUETOOTH_PERM);
        }
    }

    private boolean waitForState(Set<Integer> states) {
        int i = 0;
        while (i < 10) {
            try {
                mBluetoothLock.readLock().lock();
                if (mBluetooth == null) {
                    break;
                }
                if (states.contains(mBluetooth.getState())) {
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "getState()", e);
                break;
            } finally {
                mBluetoothLock.readLock().unlock();
            }
            SystemClock.sleep(300);
            i++;
        }
        Slog.e(TAG, "waitForState " + states + " time out");
        return false;
    }

    private void sendDisableMsg(int reason, String packageName) {
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_DISABLE));
        addActiveLog(reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_ENABLE, quietMode ? 1 : 0, 0));
        addActiveLog(reason, packageName, true);
        mLastEnabledTime = SystemClock.elapsedRealtime();
    }

    private void addActiveLog(int reason, String packageName, boolean enable) {
        synchronized (mActiveLogs) {
            if (mActiveLogs.size() > ACTIVE_LOG_MAX_SIZE) {
                mActiveLogs.remove();
            }
            mActiveLogs.add(
                    new ActiveLog(reason, packageName, enable, System.currentTimeMillis()));
        }

        int state = enable ? FrameworkStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__ENABLED :
                             FrameworkStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED__STATE__DISABLED;
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.BLUETOOTH_ENABLED_STATE_CHANGED,
                Binder.getCallingUid(), null, state, reason, packageName);
    }

    private void addCrashLog() {
        synchronized (mCrashTimestamps) {
            if (mCrashTimestamps.size() == CRASH_LOG_MAX_SIZE) {
                mCrashTimestamps.removeFirst();
            }
            mCrashTimestamps.add(System.currentTimeMillis());
            mCrashes++;
        }
    }

    private void recoverBluetoothServiceFromError(boolean clearBle) {
        Slog.e(TAG, "recoverBluetoothServiceFromError");
        try {
            mBluetoothLock.readLock().lock();
            if (mBluetooth != null) {
                //Unregister callback object
                mBluetooth.unregisterCallback(mBluetoothCallback);
            }
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to unregister", re);
        } finally {
            mBluetoothLock.readLock().unlock();
        }

        SystemClock.sleep(500);

        // disable
        addActiveLog(BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR,
                mContext.getPackageName(), false);
        handleDisable();

        waitForState(Set.of(BluetoothAdapter.STATE_OFF));

        sendBluetoothServiceDownCallback();

        try {
            mBluetoothLock.writeLock().lock();
            if (mBluetooth != null) {
                mBluetooth = null;
                // Unbind
                mContext.unbindService(mConnection);
            }
            mBluetoothGatt = null;
        } finally {
            mBluetoothLock.writeLock().unlock();
        }

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        mState = BluetoothAdapter.STATE_OFF;

        if (clearBle) {
            clearBleApps();
        }

        mEnable = false;

        // Send a Bluetooth Restart message to reenable bluetooth
        Message restartMsg = mHandler.obtainMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
        mHandler.sendMessageDelayed(restartMsg, ERROR_RESTART_TIME_MS);
    }

    private boolean isBluetoothDisallowed() {
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(UserManager.class)
                    .hasUserRestriction(UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Disables BluetoothOppLauncherActivity component, so the Bluetooth sharing option is not
     * offered to the user if Bluetooth or sharing is disallowed. Puts the component to its default
     * state if Bluetooth is not disallowed.
     *
     * @param userId user to disable bluetooth sharing for.
     * @param bluetoothSharingDisallowed whether bluetooth sharing is disallowed.
     */
    private void updateOppLauncherComponentState(int userId, boolean bluetoothSharingDisallowed) {
        final ComponentName oppLauncherComponent = new ComponentName("com.android.bluetooth",
                "com.android.bluetooth.opp.BluetoothOppLauncherActivity");
        final int newState =
                bluetoothSharingDisallowed ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {
            final IPackageManager imp = AppGlobals.getPackageManager();
            imp.setComponentEnabledSetting(oppLauncherComponent, newState,
                    PackageManager.DONT_KILL_APP, userId);
        } catch (Exception e) {
            // The component was not found, do nothing.
        }
    }

    private int getServiceRestartMs() {
        return (mErrorRecoveryRetryCounter + 1) * SERVICE_RESTART_TIME_MS;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) {
            return;
        }
        if ((args.length > 0) && args[0].startsWith("--proto")) {
            dumpProto(fd);
            return;
        }
        String errorMsg = null;

        writer.println("Bluetooth Status");
        writer.println("  enabled: " + isEnabled());
        writer.println("  state: " + BluetoothAdapter.nameForState(mState));
        writer.println("  address: " + mAddress);
        writer.println("  name: " + mName);
        if (mEnable) {
            long onDuration = SystemClock.elapsedRealtime() - mLastEnabledTime;
            String onDurationString = String.format(Locale.US, "%02d:%02d:%02d.%03d",
                    (int) (onDuration / (1000 * 60 * 60)),
                    (int) ((onDuration / (1000 * 60)) % 60), (int) ((onDuration / 1000) % 60),
                    (int) (onDuration % 1000));
            writer.println("  time since enabled: " + onDurationString);
        }

        if (mActiveLogs.size() == 0) {
            writer.println("\nBluetooth never enabled!");
        } else {
            writer.println("\nEnable log:");
            for (ActiveLog log : mActiveLogs) {
                writer.println("  " + log);
            }
        }

        writer.println(
                "\nBluetooth crashed " + mCrashes + " time" + (mCrashes == 1 ? "" : "s"));
        if (mCrashes == CRASH_LOG_MAX_SIZE) {
            writer.println("(last " + CRASH_LOG_MAX_SIZE + ")");
        }
        for (Long time : mCrashTimestamps) {
            writer.println("  " + timeToLog(time));
        }

        writer.println("\n" + mBleApps.size() + " BLE app" + (mBleApps.size() == 1 ? "" : "s")
                + " registered");
        for (ClientDeathRecipient app : mBleApps.values()) {
            writer.println("  " + app.getPackageName());
        }

        writer.println("\nBluetoothManagerService:");
        writer.println("  mEnable:" + mEnable);
        writer.println("  mQuietEnable:" + mQuietEnable);
        writer.println("  mEnableExternal:" + mEnableExternal);
        writer.println("  mQuietEnableExternal:" + mQuietEnableExternal);

        writer.println("");
        writer.flush();
        if (args.length == 0) {
            // Add arg to produce output
            args = new String[1];
            args[0] = "--print";
        }

        if (mBluetoothBinder == null) {
            errorMsg = "Bluetooth Service not connected";
        } else {
            try {
                mBluetoothBinder.dump(fd, args);
            } catch (RemoteException re) {
                errorMsg = "RemoteException while dumping Bluetooth Service";
            }
        }
        if (errorMsg != null) {
            writer.println(errorMsg);
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        proto.write(BluetoothManagerServiceDumpProto.ENABLED, isEnabled());
        proto.write(BluetoothManagerServiceDumpProto.STATE, mState);
        proto.write(BluetoothManagerServiceDumpProto.STATE_NAME,
                BluetoothAdapter.nameForState(mState));
        proto.write(BluetoothManagerServiceDumpProto.ADDRESS, mAddress);
        proto.write(BluetoothManagerServiceDumpProto.NAME, mName);
        if (mEnable) {
            proto.write(BluetoothManagerServiceDumpProto.LAST_ENABLED_TIME_MS, mLastEnabledTime);
        }
        proto.write(BluetoothManagerServiceDumpProto.CURR_TIMESTAMP_MS,
                SystemClock.elapsedRealtime());
        for (ActiveLog log : mActiveLogs) {
            long token = proto.start(BluetoothManagerServiceDumpProto.ACTIVE_LOGS);
            log.dump(proto);
            proto.end(token);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_CRASHES, mCrashes);
        proto.write(BluetoothManagerServiceDumpProto.CRASH_LOG_MAXED,
                mCrashes == CRASH_LOG_MAX_SIZE);
        for (Long time : mCrashTimestamps) {
            proto.write(BluetoothManagerServiceDumpProto.CRASH_TIMESTAMPS_MS, time);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_BLE_APPS, mBleApps.size());
        for (ClientDeathRecipient app : mBleApps.values()) {
            proto.write(BluetoothManagerServiceDumpProto.BLE_APP_PACKAGE_NAMES,
                    app.getPackageName());
        }
        proto.flush();
    }

    private static String getEnableDisableReasonString(int reason) {
        switch (reason) {
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST:
                return "APPLICATION_REQUEST";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE:
                return "AIRPLANE_MODE";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED:
                return "DISALLOWED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED:
                return "RESTARTED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR:
                return "START_ERROR";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT:
                return "SYSTEM_BOOT";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH:
                return "CRASH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH:
                return "USER_SWITCH";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING:
                return "RESTORE_USER_SETTING";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET:
                return "FACTORY_RESET";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_INIT_FLAGS_CHANGED:
                return "INIT_FLAGS_CHANGED";
            case BluetoothProtoEnums.ENABLE_DISABLE_REASON_UNSPECIFIED:
            default: return "UNKNOWN[" + reason + "]";
        }
    }
}
