/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard_obsolete;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import static android.os.BatteryManager.BATTERY_STATUS_FULL;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN;
import static android.os.BatteryManager.EXTRA_STATUS;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_HEALTH;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

import android.telephony.TelephonyManager;
import android.util.Log;
import com.android.internal.R;
import com.google.android.collect.Lists;

import java.util.ArrayList;

/**
 * Watches for updates that may be interesting to the keyguard, and provides
 * the up to date information as well as a registration for callbacks that care
 * to be updated.
 *
 * Note: under time crunch, this has been extended to include some stuff that
 * doesn't really belong here.  see {@link #handleBatteryUpdate} where it shutdowns
 * the device, and {@link #getFailedAttempts()}, {@link #reportFailedAttempt()}
 * and {@link #clearFailedAttempts()}.  Maybe we should rename this 'KeyguardContext'...
 */
public class KeyguardUpdateMonitor {

    private static final String TAG = "KeyguardUpdateMonitor";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SIM_STATES = DEBUG || false;
    private static final int FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP = 3;
    private static final int LOW_BATTERY_THRESHOLD = 20;

    // Callback messages
    private static final int MSG_TIME_UPDATE = 301;
    private static final int MSG_BATTERY_UPDATE = 302;
    private static final int MSG_CARRIER_INFO_UPDATE = 303;
    private static final int MSG_SIM_STATE_CHANGE = 304;
    private static final int MSG_RINGER_MODE_CHANGED = 305;
    private static final int MSG_PHONE_STATE_CHANGED = 306;
    private static final int MSG_CLOCK_VISIBILITY_CHANGED = 307;
    private static final int MSG_DEVICE_PROVISIONED = 308;
    protected static final int MSG_DPM_STATE_CHANGED = 309;
    protected static final int MSG_USER_SWITCHED = 310;
    protected static final int MSG_USER_REMOVED = 311;

    private final Context mContext;

    // Telephony state
    private IccCardConstants.State mSimState = IccCardConstants.State.READY;
    private CharSequence mTelephonyPlmn;
    private CharSequence mTelephonySpn;
    private int mRingMode;
    private int mPhoneState;

    private boolean mDeviceProvisioned;

    private BatteryStatus mBatteryStatus;

    private int mFailedAttempts = 0;
    private int mFailedBiometricUnlockAttempts = 0;

    private boolean mClockVisible;

    private ArrayList<KeyguardUpdateMonitorCallback> mCallbacks = Lists.newArrayList();
    private ContentObserver mContentObserver;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TIME_UPDATE:
                    handleTimeUpdate();
                    break;
                case MSG_BATTERY_UPDATE:
                    handleBatteryUpdate((BatteryStatus) msg.obj);
                    break;
                case MSG_CARRIER_INFO_UPDATE:
                    handleCarrierInfoUpdate();
                    break;
                case MSG_SIM_STATE_CHANGE:
                    handleSimStateChange((SimArgs) msg.obj);
                    break;
                case MSG_RINGER_MODE_CHANGED:
                    handleRingerModeChange(msg.arg1);
                    break;
                case MSG_PHONE_STATE_CHANGED:
                    handlePhoneStateChanged((String)msg.obj);
                    break;
                case MSG_CLOCK_VISIBILITY_CHANGED:
                    handleClockVisibilityChanged();
                    break;
                case MSG_DEVICE_PROVISIONED:
                    handleDeviceProvisioned();
                    break;
                case MSG_DPM_STATE_CHANGED:
                    handleDevicePolicyManagerStateChanged();
                    break;
                case MSG_USER_SWITCHED:
                    handleUserSwitched(msg.arg1);
                    break;
                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    break;
            }
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "received broadcast " + action);

            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_TIME_UPDATE));
            } else if (TelephonyIntents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                mTelephonyPlmn = getTelephonyPlmnFrom(intent);
                mTelephonySpn = getTelephonySpnFrom(intent);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_CARRIER_INFO_UPDATE));
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                final int status = intent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
                final int plugged = intent.getIntExtra(EXTRA_PLUGGED, 0);
                final int level = intent.getIntExtra(EXTRA_LEVEL, 0);
                final int health = intent.getIntExtra(EXTRA_HEALTH, BATTERY_HEALTH_UNKNOWN);
                final Message msg = mHandler.obtainMessage(
                        MSG_BATTERY_UPDATE, new BatteryStatus(status, level, plugged, health));
                mHandler.sendMessage(msg);
            } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                if (DEBUG_SIM_STATES) {
                    Log.v(TAG, "action " + action + " state" +
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE));
                }
                mHandler.sendMessage(mHandler.obtainMessage(
                        MSG_SIM_STATE_CHANGE, SimArgs.fromIntent(intent)));
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_RINGER_MODE_CHANGED,
                        intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1), 0));
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PHONE_STATE_CHANGED, state));
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DPM_STATE_CHANGED));
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0), 0));
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_REMOVED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0), 0));
            }
        }
    };

    /**
     * When we receive a
     * {@link com.android.internal.telephony.TelephonyIntents#ACTION_SIM_STATE_CHANGED} broadcast,
     * and then pass a result via our handler to {@link KeyguardUpdateMonitor#handleSimStateChange},
     * we need a single object to pass to the handler.  This class helps decode
     * the intent and provide a {@link SimCard.State} result.
     */
    private static class SimArgs {
        public final IccCardConstants.State simState;

        SimArgs(IccCardConstants.State state) {
            simState = state;
        }

        static SimArgs fromIntent(Intent intent) {
            IccCardConstants.State state;
            if (!TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                throw new IllegalArgumentException("only handles intent ACTION_SIM_STATE_CHANGED");
            }
            String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                final String absentReason = intent
                    .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);

                if (IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED.equals(
                        absentReason)) {
                    state = IccCardConstants.State.PERM_DISABLED;
                } else {
                    state = IccCardConstants.State.ABSENT;
                }
            } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
                state = IccCardConstants.State.READY;
            } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                final String lockedReason = intent
                        .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                    state = IccCardConstants.State.PIN_REQUIRED;
                } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                    state = IccCardConstants.State.PUK_REQUIRED;
                } else {
                    state = IccCardConstants.State.UNKNOWN;
                }
            } else if (IccCardConstants.INTENT_VALUE_LOCKED_NETWORK.equals(stateExtra)) {
                state = IccCardConstants.State.NETWORK_LOCKED;
            } else {
                state = IccCardConstants.State.UNKNOWN;
            }
            return new SimArgs(state);
        }

        public String toString() {
            return simState.toString();
        }
    }

    /* package */ static class BatteryStatus {
        public final int status;
        public final int level;
        public final int plugged;
        public final int health;
        public BatteryStatus(int status, int level, int plugged, int health) {
            this.status = status;
            this.level = level;
            this.plugged = plugged;
            this.health = health;
        }

        /**
         * Determine whether the device is plugged in (USB or power).
         * @return true if the device is plugged in.
         */
        boolean isPluggedIn() {
            return plugged == BatteryManager.BATTERY_PLUGGED_AC
                    || plugged == BatteryManager.BATTERY_PLUGGED_USB
                    || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        }

        /**
         * Whether or not the device is charged. Note that some devices never return 100% for
         * battery level, so this allows either battery level or status to determine if the
         * battery is charged.
         * @return true if the device is charged
         */
        public boolean isCharged() {
            return status == BATTERY_STATUS_FULL || level >= 100;
        }

        /**
         * Whether battery is low and needs to be charged.
         * @return true if battery is low
         */
        public boolean isBatteryLow() {
            return level < LOW_BATTERY_THRESHOLD;
        }

    }

    public KeyguardUpdateMonitor(Context context) {
        mContext = context;

        mDeviceProvisioned = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;

        // Since device can't be un-provisioned, we only need to register a content observer
        // to update mDeviceProvisioned when we are...
        if (!mDeviceProvisioned) {
            watchForDeviceProvisioning();
        }

        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        mSimState = IccCardConstants.State.NOT_READY;
        mBatteryStatus = new BatteryStatus(BATTERY_STATUS_UNKNOWN, 100, 0, 0);
        mTelephonyPlmn = getDefaultPlmn();

        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    private void watchForDeviceProvisioning() {
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mDeviceProvisioned = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0;
                if (mDeviceProvisioned) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_DEVICE_PROVISIONED));
                }
                if (DEBUG) Log.d(TAG, "DEVICE_PROVISIONED state = " + mDeviceProvisioned);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mContentObserver);

        // prevent a race condition between where we check the flag and where we register the
        // observer by grabbing the value once again...
        boolean provisioned = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (provisioned != mDeviceProvisioned) {
            mDeviceProvisioned = provisioned;
            if (mDeviceProvisioned) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DEVICE_PROVISIONED));
            }
        }
    }

    /**
     * Handle {@link #MSG_DPM_STATE_CHANGED}
     */
    protected void handleDevicePolicyManagerStateChanged() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onDevicePolicyManagerStateChanged();
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCHED}
     */
    protected void handleUserSwitched(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onUserSwitched(userId);
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCHED}
     */
    protected void handleUserRemoved(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onUserRemoved(userId);
        }
    }

    /**
     * Handle {@link #MSG_DEVICE_PROVISIONED}
     */
    protected void handleDeviceProvisioned() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onDeviceProvisioned();
        }
        if (mContentObserver != null) {
            // We don't need the observer anymore...
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
            mContentObserver = null;
        }
    }

    /**
     * Handle {@link #MSG_PHONE_STATE_CHANGED}
     */
    protected void handlePhoneStateChanged(String newState) {
        if (DEBUG) Log.d(TAG, "handlePhoneStateChanged(" + newState + ")");
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_IDLE;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_OFFHOOK;
        } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_RINGING;
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onPhoneStateChanged(mPhoneState);
        }
    }

    /**
     * Handle {@link #MSG_RINGER_MODE_CHANGED}
     */
    protected void handleRingerModeChange(int mode) {
        if (DEBUG) Log.d(TAG, "handleRingerModeChange(" + mode + ")");
        mRingMode = mode;
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onRingerModeChanged(mode);
        }
    }

    /**
     * Handle {@link #MSG_TIME_UPDATE}
     */
    private void handleTimeUpdate() {
        if (DEBUG) Log.d(TAG, "handleTimeUpdate");
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onTimeChanged();
        }
    }

    /**
     * Handle {@link #MSG_BATTERY_UPDATE}
     */
    private void handleBatteryUpdate(BatteryStatus status) {
        if (DEBUG) Log.d(TAG, "handleBatteryUpdate");
        final boolean batteryUpdateInteresting = isBatteryUpdateInteresting(mBatteryStatus, status);
        mBatteryStatus = status;
        if (batteryUpdateInteresting) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onRefreshBatteryInfo(status);
            }
        }
    }

    /**
     * Handle {@link #MSG_CARRIER_INFO_UPDATE}
     */
    private void handleCarrierInfoUpdate() {
        if (DEBUG) Log.d(TAG, "handleCarrierInfoUpdate: plmn = " + mTelephonyPlmn
            + ", spn = " + mTelephonySpn);

        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onRefreshCarrierInfo(mTelephonyPlmn, mTelephonySpn);
        }
    }

    /**
     * Handle {@link #MSG_SIM_STATE_CHANGE}
     */
    private void handleSimStateChange(SimArgs simArgs) {
        final IccCardConstants.State state = simArgs.simState;

        if (DEBUG) {
            Log.d(TAG, "handleSimStateChange: intentValue = " + simArgs + " "
                    + "state resolved to " + state.toString());
        }

        if (state != IccCardConstants.State.UNKNOWN && state != mSimState) {
            mSimState = state;
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onSimStateChanged(state);
            }
        }
    }

    /**
     * Handle {@link #MSG_CLOCK_VISIBILITY_CHANGED}
     */
    private void handleClockVisibilityChanged() {
        if (DEBUG) Log.d(TAG, "handleClockVisibilityChanged()");
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onClockVisibilityChanged();
        }
    }

    private static boolean isBatteryUpdateInteresting(BatteryStatus old, BatteryStatus current) {
        final boolean nowPluggedIn = current.isPluggedIn();
        final boolean wasPluggedIn = old.isPluggedIn();
        final boolean stateChangedWhilePluggedIn =
            wasPluggedIn == true && nowPluggedIn == true
            && (old.status != current.status);

        // change in plug state is always interesting
        if (wasPluggedIn != nowPluggedIn || stateChangedWhilePluggedIn) {
            return true;
        }

        // change in battery level while plugged in
        if (nowPluggedIn && old.level != current.level) {
            return true;
        }

        // change where battery needs charging
        if (!nowPluggedIn && current.isBatteryLow() && current.level != old.level) {
            return true;
        }
        return false;
    }

    /**
     * @param intent The intent with action {@link TelephonyIntents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonyPlmnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false)) {
            final String plmn = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
            return (plmn != null) ? plmn : getDefaultPlmn();
        }
        return null;
    }

    /**
     * @return The default plmn (no service)
     */
    private CharSequence getDefaultPlmn() {
        return mContext.getResources().getText(R.string.lockscreen_carrier_default);
    }

    /**
     * @param intent The intent with action {@link Telephony.Intents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonySpnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false)) {
            final String spn = intent.getStringExtra(TelephonyIntents.EXTRA_SPN);
            if (spn != null) {
                return spn;
            }
        }
        return null;
    }

    /**
     * Remove the given observer's callback.
     *
     * @param observer The observer to remove
     */
    public void removeCallback(Object observer) {
        mCallbacks.remove(observer);
    }

    /**
     * Register to receive notifications about general keyguard information
     * (see {@link InfoCallback}.
     * @param callback The callback.
     */
    public void registerCallback(KeyguardUpdateMonitorCallback callback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
            // Notify listener of the current state
            callback.onRefreshBatteryInfo(mBatteryStatus);
            callback.onTimeChanged();
            callback.onRingerModeChanged(mRingMode);
            callback.onPhoneStateChanged(mPhoneState);
            callback.onRefreshCarrierInfo(mTelephonyPlmn, mTelephonySpn);
            callback.onClockVisibilityChanged();
            callback.onSimStateChanged(mSimState);
        } else {
            if (DEBUG) Log.e(TAG, "Object tried to add another callback",
                    new Exception("Called by"));
        }
    }

    public void reportClockVisible(boolean visible) {
        mClockVisible = visible;
        mHandler.obtainMessage(MSG_CLOCK_VISIBILITY_CHANGED).sendToTarget();
    }

    public IccCardConstants.State getSimState() {
        return mSimState;
    }

    /**
     * Report that the user successfully entered the SIM PIN or PUK/SIM PIN so we
     * have the information earlier than waiting for the intent
     * broadcast from the telephony code.
     *
     * NOTE: Because handleSimStateChange() invokes callbacks immediately without going
     * through mHandler, this *must* be called from the UI thread.
     */
    public void reportSimUnlocked() {
        handleSimStateChange(new SimArgs(IccCardConstants.State.READY));
    }

    public CharSequence getTelephonyPlmn() {
        return mTelephonyPlmn;
    }

    public CharSequence getTelephonySpn() {
        return mTelephonySpn;
    }

    /**
     * @return Whether the device is provisioned (whether they have gone through
     *   the setup wizard)
     */
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    public int getFailedAttempts() {
        return mFailedAttempts;
    }

    public void clearFailedAttempts() {
        mFailedAttempts = 0;
        mFailedBiometricUnlockAttempts = 0;
    }

    public void reportFailedAttempt() {
        mFailedAttempts++;
    }

    public boolean isClockVisible() {
        return mClockVisible;
    }

    public int getPhoneState() {
        return mPhoneState;
    }

    public void reportFailedBiometricUnlockAttempt() {
        mFailedBiometricUnlockAttempts++;
    }

    public boolean getMaxBiometricUnlockAttemptsReached() {
        return mFailedBiometricUnlockAttempts >= FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP;
    }

    public boolean isSimLocked() {
        return mSimState == IccCardConstants.State.PIN_REQUIRED
            || mSimState == IccCardConstants.State.PUK_REQUIRED
            || mSimState == IccCardConstants.State.PERM_DISABLED;
    }
}
