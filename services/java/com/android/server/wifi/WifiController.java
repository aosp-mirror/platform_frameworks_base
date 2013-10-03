/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
import static android.net.wifi.WifiManager.WIFI_MODE_SCAN_ONLY;
import android.net.wifi.WifiStateMachine;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiService.LockList;

import java.io.FileDescriptor;
import java.io.PrintWriter;

class WifiController extends StateMachine {
    private static final String TAG = "WifiController";
    private static final boolean DBG = false;
    private Context mContext;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;
    private int mStayAwakeConditions;
    private long mIdleMillis;
    private int mSleepPolicy;
    private boolean mFirstUserSignOnSeen = false;

    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;

    /**
     * See {@link Settings.Global#WIFI_IDLE_MS}. This is the default value if a
     * Settings.Global value is not present. This timeout value is chosen as
     * the approximate point at which the battery drain caused by Wi-Fi
     * being enabled but not active exceeds the battery drain caused by
     * re-establishing a connection to the mobile data network.
     */
    private static final long DEFAULT_IDLE_MS = 15 * 60 * 1000; /* 15 minutes */

    /**
     * See {@link Settings.Global#WIFI_REENABLE_DELAY_MS}.  This is the default value if a
     * Settings.Global value is not present.  This is the minimum time after wifi is disabled
     * we'll act on an enable.  Enable requests received before this delay will be deferred.
     */
    private static final long DEFAULT_REENABLE_DELAY_MS = 500;

    // finding that delayed messages can sometimes be delivered earlier than expected
    // probably rounding errors..  add a margin to prevent problems
    private static final long DEFER_MARGIN_MS = 5;

    NetworkInfo mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");

    private static final String ACTION_DEVICE_IDLE =
            "com.android.server.WifiManager.action.DEVICE_IDLE";

    /* References to values tracked in WifiService */
    final WifiStateMachine mWifiStateMachine;
    final WifiSettingsStore mSettingsStore;
    final LockList mLocks;

    /**
     * Temporary for computing UIDS that are responsible for starting WIFI.
     * Protected by mWifiStateTracker lock.
     */
    private final WorkSource mTmpWorkSource = new WorkSource();

    private long mReEnableDelayMillis;

    private static final int BASE = Protocol.BASE_WIFI_CONTROLLER;

    static final int CMD_EMERGENCY_MODE_CHANGED     = BASE + 1;
    static final int CMD_SCREEN_ON                  = BASE + 2;
    static final int CMD_SCREEN_OFF                 = BASE + 3;
    static final int CMD_BATTERY_CHANGED            = BASE + 4;
    static final int CMD_DEVICE_IDLE                = BASE + 5;
    static final int CMD_LOCKS_CHANGED              = BASE + 6;
    static final int CMD_SCAN_ALWAYS_MODE_CHANGED   = BASE + 7;
    static final int CMD_WIFI_TOGGLED               = BASE + 8;
    static final int CMD_AIRPLANE_TOGGLED           = BASE + 9;
    static final int CMD_SET_AP                     = BASE + 10;
    static final int CMD_DEFERRED_TOGGLE            = BASE + 11;
    static final int CMD_USER_PRESENT               = BASE + 12;

    private DefaultState mDefaultState = new DefaultState();
    private StaEnabledState mStaEnabledState = new StaEnabledState();
    private ApStaDisabledState mApStaDisabledState = new ApStaDisabledState();
    private StaDisabledWithScanState mStaDisabledWithScanState = new StaDisabledWithScanState();
    private ApEnabledState mApEnabledState = new ApEnabledState();
    private DeviceActiveState mDeviceActiveState = new DeviceActiveState();
    private DeviceInactiveState mDeviceInactiveState = new DeviceInactiveState();
    private ScanOnlyLockHeldState mScanOnlyLockHeldState = new ScanOnlyLockHeldState();
    private FullLockHeldState mFullLockHeldState = new FullLockHeldState();
    private FullHighPerfLockHeldState mFullHighPerfLockHeldState = new FullHighPerfLockHeldState();
    private NoLockHeldState mNoLockHeldState = new NoLockHeldState();
    private EcmState mEcmState = new EcmState();

    WifiController(Context context, WifiService service, Looper looper) {
        super(TAG, looper);
        mContext = context;
        mWifiStateMachine = service.mWifiStateMachine;
        mSettingsStore = service.mSettingsStore;
        mLocks = service.mLocks;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = PendingIntent.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        addState(mDefaultState);
            addState(mApStaDisabledState, mDefaultState);
            addState(mStaEnabledState, mDefaultState);
                addState(mDeviceActiveState, mStaEnabledState);
                addState(mDeviceInactiveState, mStaEnabledState);
                    addState(mScanOnlyLockHeldState, mDeviceInactiveState);
                    addState(mFullLockHeldState, mDeviceInactiveState);
                    addState(mFullHighPerfLockHeldState, mDeviceInactiveState);
                    addState(mNoLockHeldState, mDeviceInactiveState);
            addState(mStaDisabledWithScanState, mDefaultState);
            addState(mApEnabledState, mDefaultState);
            addState(mEcmState, mDefaultState);
        if (mSettingsStore.isScanAlwaysAvailable()) {
            setInitialState(mStaDisabledWithScanState);
        } else {
            setInitialState(mApStaDisabledState);
        }
        setLogRecSize(100);
        setLogOnlyTransitions(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DEVICE_IDLE);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(ACTION_DEVICE_IDLE)) {
                            sendMessage(CMD_DEVICE_IDLE);
                        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                            mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_NETWORK_INFO);
                        }
                    }
                },
                new IntentFilter(filter));

        initializeAndRegisterForSettingsChange(looper);
    }

    private void initializeAndRegisterForSettingsChange(Looper looper) {
        Handler handler = new Handler(looper);
        readStayAwakeConditions();
        registerForStayAwakeModeChange(handler);
        readWifiIdleTime();
        registerForWifiIdleTimeChange(handler);
        readWifiSleepPolicy();
        registerForWifiSleepPolicyChange(handler);
        readWifiReEnableDelay();
    }

    private void readStayAwakeConditions() {
        mStayAwakeConditions = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
    }

    private void readWifiIdleTime() {
        mIdleMillis = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.WIFI_IDLE_MS, DEFAULT_IDLE_MS);
    }

    private void readWifiSleepPolicy() {
        mSleepPolicy = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SLEEP_POLICY,
                Settings.Global.WIFI_SLEEP_POLICY_NEVER);
    }

    private void readWifiReEnableDelay() {
        mReEnableDelayMillis = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.WIFI_REENABLE_DELAY_MS, DEFAULT_REENABLE_DELAY_MS);
    }

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForStayAwakeModeChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readStayAwakeConditions();
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                false, contentObserver);
    }

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForWifiIdleTimeChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readWifiIdleTime();
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_IDLE_MS),
                false, contentObserver);
    }

    /**
     * Observes changes to wifi sleep policy
     */
    private void registerForWifiSleepPolicyChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readWifiSleepPolicy();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_SLEEP_POLICY),
                false, contentObserver);
    }

    /**
     * Determines whether the Wi-Fi chipset should stay awake or be put to
     * sleep. Looks at the setting for the sleep policy and the current
     * conditions.
     *
     * @see #shouldDeviceStayAwake(int)
     */
    private boolean shouldWifiStayAwake(int pluggedType) {
        if (mSleepPolicy == Settings.Global.WIFI_SLEEP_POLICY_NEVER) {
            // Never sleep
            return true;
        } else if ((mSleepPolicy == Settings.Global.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED) &&
                (pluggedType != 0)) {
            // Never sleep while plugged, and we're plugged
            return true;
        } else {
            // Default
            return shouldDeviceStayAwake(pluggedType);
        }
    }

    /**
     * Determine whether the bit value corresponding to {@code pluggedType} is set in
     * the bit string mStayAwakeConditions. This determines whether the device should
     * stay awake based on the current plugged type.
     *
     * @param pluggedType the type of plug (USB, AC, or none) for which the check is
     * being made
     * @return {@code true} if {@code pluggedType} indicates that the device is
     * supposed to stay awake, {@code false} otherwise.
     */
    private boolean shouldDeviceStayAwake(int pluggedType) {
        return (mStayAwakeConditions & pluggedType) != 0;
    }

    private void updateBatteryWorkSource() {
        mTmpWorkSource.clear();
        if (mDeviceIdle) {
            mLocks.updateWorkSource(mTmpWorkSource);
        }
        mWifiStateMachine.updateBatteryWorkSource(mTmpWorkSource);
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_SCREEN_ON:
                    mAlarmManager.cancel(mIdleIntent);
                    mScreenOff = false;
                    mDeviceIdle = false;
                    updateBatteryWorkSource();
                    break;
                case CMD_SCREEN_OFF:
                    mScreenOff = true;
                    /*
                    * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                    * AND the "stay on while plugged in" setting doesn't match the
                    * current power conditions (i.e, not plugged in, plugged in to USB,
                    * or plugged in to AC).
                    */
                    if (!shouldWifiStayAwake(mPluggedType)) {
                        //Delayed shutdown if wifi is connected
                        if (mNetworkInfo.getDetailedState() ==
                                NetworkInfo.DetailedState.CONNECTED) {
                            if (DBG) Slog.d(TAG, "set idle timer: " + mIdleMillis + " ms");
                            mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                    System.currentTimeMillis() + mIdleMillis, mIdleIntent);
                        } else {
                            sendMessage(CMD_DEVICE_IDLE);
                        }
                    }
                    break;
                case CMD_DEVICE_IDLE:
                    mDeviceIdle = true;
                    updateBatteryWorkSource();
                    break;
                case CMD_BATTERY_CHANGED:
                    /*
                    * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                    * AND we are transitioning from a state in which the device was supposed
                    * to stay awake to a state in which it is not supposed to stay awake.
                    * If "stay awake" state is not changing, we do nothing, to avoid resetting
                    * the already-set timer.
                    */
                    int pluggedType = msg.arg1;
                    if (DBG) Slog.d(TAG, "battery changed pluggedType: " + pluggedType);
                    if (mScreenOff && shouldWifiStayAwake(mPluggedType) &&
                            !shouldWifiStayAwake(pluggedType)) {
                        long triggerTime = System.currentTimeMillis() + mIdleMillis;
                        if (DBG) Slog.d(TAG, "set idle timer for " + mIdleMillis + "ms");
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    }

                    mPluggedType = pluggedType;
                    break;
                case CMD_SET_AP:
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                case CMD_LOCKS_CHANGED:
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    break;
                case CMD_USER_PRESENT:
                    mFirstUserSignOnSeen = true;
                    break;
                case CMD_DEFERRED_TOGGLE:
                    log("DEFERRED_TOGGLE ignored due to state change");
                    break;
                default:
                    throw new RuntimeException("WifiController.handleMessage " + msg.what);
            }
            return HANDLED;
        }

    }

    class ApStaDisabledState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            mWifiStateMachine.setSupplicantRunning(false);
            // Supplicant can't restart right away, so not the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
        }
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                //  have 2 toggles now, inc serial number an ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        if (mDeviceIdle == false) {
                            transitionTo(mDeviceActiveState);
                        } else {
                            checkLocksAndTransitionWhenDeviceIdle();
                        }
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        mWifiStateMachine.setHostApRunning((WifiConfiguration) msg.obj,
                                true);
                        transitionTo(mApEnabledState);
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    log("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    class StaEnabledState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setSupplicantRunning(true);
        }
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        if (mSettingsStore.isScanAlwaysAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        } else {
                            transitionTo(mApStaDisabledState);
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    /* When wi-fi is turned off due to airplane,
                    * disable entirely (including scan)
                    */
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_EMERGENCY_MODE_CHANGED:
                    if (msg.arg1 == 1) {
                        transitionTo(mEcmState);
                        break;
                    }
                default:
                    return NOT_HANDLED;

            }
            return HANDLED;
        }
    }

    class StaDisabledWithScanState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            mWifiStateMachine.setSupplicantRunning(true);
            mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_WITH_WIFI_OFF_MODE);
            mWifiStateMachine.setDriverStart(true);
            // Supplicant can't restart right away, so not the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                // have 2 toggles now, inc serial number and ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        if (mDeviceIdle == false) {
                            transitionTo(mDeviceActiveState);
                        } else {
                            checkLocksAndTransitionWhenDeviceIdle();
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn() &&
                            ! mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mApStaDisabledState);
                    }
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (! mSettingsStore.isScanAlwaysAvailable()) {
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_SET_AP:
                    // Before starting tethering, turn off supplicant for scan mode
                    if (msg.arg1 == 1) {
                        deferMessage(msg);
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    logd("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    class ApEnabledState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn()) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 0) {
                        mWifiStateMachine.setHostApRunning(null, false);
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class EcmState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setSupplicantRunning(false);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what == CMD_EMERGENCY_MODE_CHANGED && msg.arg1 == 0) {
                if (mSettingsStore.isWifiToggleEnabled()) {
                    if (mDeviceIdle == false) {
                        transitionTo(mDeviceActiveState);
                    } else {
                        checkLocksAndTransitionWhenDeviceIdle();
                    }
                } else if (mSettingsStore.isScanAlwaysAvailable()) {
                    transitionTo(mStaDisabledWithScanState);
                } else {
                    transitionTo(mApStaDisabledState);
                }
                return HANDLED;
            } else {
                return NOT_HANDLED;
            }
        }
    }

    /* Parent: StaEnabledState */
    class DeviceActiveState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setDriverStart(true);
            mWifiStateMachine.setHighPerfModeEnabled(false);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what == CMD_DEVICE_IDLE) {
                checkLocksAndTransitionWhenDeviceIdle();
                // We let default state handle the rest of work
            } else if (msg.what == CMD_USER_PRESENT) {
                // TLS networks can't connect until user unlocks keystore. KeyStore
                // unlocks when the user punches PIN after the reboot. So use this
                // trigger to get those networks connected.
                if (mFirstUserSignOnSeen == false) {
                    mWifiStateMachine.reloadTlsNetworksAndReconnect();
                }
                mFirstUserSignOnSeen = true;
                return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /* Parent: StaEnabledState */
    class DeviceInactiveState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_LOCKS_CHANGED:
                    checkLocksAndTransitionWhenDeviceIdle();
                    updateBatteryWorkSource();
                    return HANDLED;
                case CMD_SCREEN_ON:
                    transitionTo(mDeviceActiveState);
                    // More work in default state
                    return NOT_HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a scan only lock. */
    class ScanOnlyLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_MODE);
            mWifiStateMachine.setDriverStart(true);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a full lock. */
    class FullLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setDriverStart(true);
            mWifiStateMachine.setHighPerfModeEnabled(false);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a high perf lock. */
    class FullHighPerfLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setDriverStart(true);
            mWifiStateMachine.setHighPerfModeEnabled(true);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive and no app is holding a wifi lock. */
    class NoLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setDriverStart(false);
        }
    }

    private void checkLocksAndTransitionWhenDeviceIdle() {
        if (mLocks.hasLocks()) {
            switch (mLocks.getStrongestLockMode()) {
                case WIFI_MODE_FULL:
                    transitionTo(mFullLockHeldState);
                    break;
                case WIFI_MODE_FULL_HIGH_PERF:
                    transitionTo(mFullHighPerfLockHeldState);
                    break;
                case WIFI_MODE_SCAN_ONLY:
                    transitionTo(mScanOnlyLockHeldState);
                    break;
                default:
                    loge("Illegal lock " + mLocks.getStrongestLockMode());
            }
        } else {
            if (mSettingsStore.isScanAlwaysAvailable()) {
                transitionTo(mScanOnlyLockHeldState);
            } else {
                transitionTo(mNoLockHeldState);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);

        pw.println("mScreenOff " + mScreenOff);
        pw.println("mDeviceIdle " + mDeviceIdle);
        pw.println("mPluggedType " + mPluggedType);
        pw.println("mIdleMillis " + mIdleMillis);
        pw.println("mSleepPolicy " + mSleepPolicy);
    }
}
