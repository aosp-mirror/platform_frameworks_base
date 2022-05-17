/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.power;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.net.NetworkPolicyManagerInternal;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Controls Low Power Standby state.
 *
 * Instantiated by {@link PowerManagerService} only if Low Power Standby is supported.
 *
 * <p>Low Power Standby is active when all of the following conditions are met:
 * <ul>
 *   <li>Low Power Standby is enabled
 *   <li>The device is not interactive, and has been non-interactive for a given timeout
 *   <li>The device is not in a doze maintenance window
 * </ul>
 *
 * <p>When Low Power Standby is active, the following restrictions are applied to applications
 * with procstate less important than {@link android.app.ActivityManager#PROCESS_STATE_BOUND_TOP}:
 * <ul>
 *   <li>Network access is blocked
 *   <li>Wakelocks are disabled
 * </ul>
 *
 * @hide
 */
public class LowPowerStandbyController {
    private static final String TAG = "LowPowerStandbyController";
    private static final boolean DEBUG = false;
    private static final boolean DEFAULT_ACTIVE_DURING_MAINTENANCE = false;

    private static final int MSG_STANDBY_TIMEOUT = 0;
    private static final int MSG_NOTIFY_ACTIVE_CHANGED = 1;
    private static final int MSG_NOTIFY_ALLOWLIST_CHANGED = 2;

    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final Object mLock = new Object();

    private final Context mContext;
    private final Clock mClock;
    private final AlarmManager.OnAlarmListener mOnStandbyTimeoutExpired =
            this::onStandbyTimeoutExpired;
    private final LowPowerStandbyControllerInternal mLocalService = new LocalService();
    private final SparseBooleanArray mAllowlistUids = new SparseBooleanArray();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    onNonInteractive();
                    break;
                case Intent.ACTION_SCREEN_ON:
                    onInteractive();
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    onDeviceIdleModeChanged();
                    break;
            }
        }
    };

    @GuardedBy("mLock")
    private AlarmManager mAlarmManager;
    @GuardedBy("mLock")
    private PowerManager mPowerManager;
    @GuardedBy("mLock")
    private boolean mSupportedConfig;
    @GuardedBy("mLock")
    private boolean mEnabledByDefaultConfig;
    @GuardedBy("mLock")
    private int mStandbyTimeoutConfig;

    /** Whether Low Power Standby is enabled in Settings */
    @GuardedBy("mLock")
    private boolean mIsEnabled;

    /**
     * Whether Low Power Standby is currently active (enforcing restrictions).
     */
    @GuardedBy("mLock")
    private boolean mIsActive;

    /** Whether the device is currently interactive */
    @GuardedBy("mLock")
    private boolean mIsInteractive;

    /** The time the device was last interactive, in {@link SystemClock#elapsedRealtime()}. */
    @GuardedBy("mLock")
    private long mLastInteractiveTimeElapsed;

    /**
     * Whether we are in device idle mode.
     * During maintenance windows Low Power Standby is deactivated to allow
     * apps to run maintenance tasks.
     */
    @GuardedBy("mLock")
    private boolean mIsDeviceIdle;

    /**
     * Whether the device has entered idle mode since becoming non-interactive.
     * In the initial non-idle period after turning the screen off, Low Power Standby is already
     * allowed to become active. Later non-idle periods are treated as maintenance windows, during
     * which Low Power Standby is deactivated to allow apps to run maintenance tasks.
     */
    @GuardedBy("mLock")
    private boolean mIdleSinceNonInteractive;

    /** Whether Low Power Standby restrictions should be active during doze maintenance mode. */
    @GuardedBy("mLock")
    private boolean mActiveDuringMaintenance;

    /** Force Low Power Standby to be active. */
    @GuardedBy("mLock")
    private boolean mForceActive;

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /** Returns milliseconds since boot, including time spent in sleep. */
        long elapsedRealtime();
    }

    public LowPowerStandbyController(Context context, Looper looper, Clock clock) {
        mContext = context;
        mHandler = new LowPowerStandbyHandler(looper);
        mClock = clock;
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    /** Call when system services are ready */
    @VisibleForTesting
    public void systemReady() {
        final Resources resources = mContext.getResources();
        synchronized (mLock) {
            mSupportedConfig = resources.getBoolean(
                    com.android.internal.R.bool.config_lowPowerStandbySupported);

            if (!mSupportedConfig) {
                return;
            }

            mAlarmManager = mContext.getSystemService(AlarmManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);

            mStandbyTimeoutConfig = resources.getInteger(
                    R.integer.config_lowPowerStandbyNonInteractiveTimeout);
            mEnabledByDefaultConfig = resources.getBoolean(
                    R.bool.config_lowPowerStandbyEnabledByDefault);

            mIsInteractive = mPowerManager.isInteractive();

            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_STANDBY_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            updateSettingsLocked();

            if (mIsEnabled) {
                registerBroadcastReceiver();
            }
        }

        LocalServices.addService(LowPowerStandbyControllerInternal.class, mLocalService);
    }

    @GuardedBy("mLock")
    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        mIsEnabled = mSupportedConfig && Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_STANDBY_ENABLED,
                mEnabledByDefaultConfig ? 1 : 0) != 0;
        mActiveDuringMaintenance = Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE,
                DEFAULT_ACTIVE_DURING_MAINTENANCE ? 1 : 0) != 0;

        updateActiveLocked();
    }

    @GuardedBy("mLock")
    private void updateActiveLocked() {
        final long now = mClock.elapsedRealtime();
        final boolean standbyTimeoutExpired =
                (now - mLastInteractiveTimeElapsed) >= mStandbyTimeoutConfig;
        final boolean maintenanceMode = mIdleSinceNonInteractive && !mIsDeviceIdle;
        final boolean newActive =
                mForceActive || (mIsEnabled && !mIsInteractive && standbyTimeoutExpired
                        && (!maintenanceMode || mActiveDuringMaintenance));
        if (DEBUG) {
            Slog.d(TAG, "updateActiveLocked: mIsEnabled=" + mIsEnabled + ", mIsInteractive="
                    + mIsInteractive + ", standbyTimeoutExpired=" + standbyTimeoutExpired
                    + ", mIdleSinceNonInteractive=" + mIdleSinceNonInteractive + ", mIsDeviceIdle="
                    + mIsDeviceIdle + ", mActiveDuringMaintenance=" + mActiveDuringMaintenance
                    + ", mForceActive=" + mForceActive + ", mIsActive=" + mIsActive + ", newActive="
                    + newActive);
        }
        if (mIsActive != newActive) {
            mIsActive = newActive;
            if (DEBUG) {
                Slog.d(TAG, "mIsActive changed, mIsActive=" + mIsActive);
            }
            enqueueNotifyActiveChangedLocked();
        }
    }

    private void onNonInteractive() {
        if (DEBUG) {
            Slog.d(TAG, "onNonInteractive");
        }
        final long now = mClock.elapsedRealtime();
        synchronized (mLock) {
            mIsInteractive = false;
            mIsDeviceIdle = false;
            mLastInteractiveTimeElapsed = now;

            if (mStandbyTimeoutConfig > 0) {
                scheduleStandbyTimeoutAlarmLocked();
            }

            updateActiveLocked();
        }
    }

    private void onInteractive() {
        if (DEBUG) {
            Slog.d(TAG, "onInteractive");
        }

        synchronized (mLock) {
            cancelStandbyTimeoutAlarmLocked();
            mIsInteractive = true;
            mIsDeviceIdle = false;
            mIdleSinceNonInteractive = false;
            updateActiveLocked();
        }
    }

    @GuardedBy("mLock")
    private void scheduleStandbyTimeoutAlarmLocked() {
        final long nextAlarmTime = SystemClock.elapsedRealtime() + mStandbyTimeoutConfig;
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                nextAlarmTime, "LowPowerStandbyController.StandbyTimeout",
                mOnStandbyTimeoutExpired, mHandler);
    }

    @GuardedBy("mLock")
    private void cancelStandbyTimeoutAlarmLocked() {
        mAlarmManager.cancel(mOnStandbyTimeoutExpired);
    }

    private void onDeviceIdleModeChanged() {
        synchronized (mLock) {
            mIsDeviceIdle = mPowerManager.isDeviceIdleMode();
            if (DEBUG) {
                Slog.d(TAG, "onDeviceIdleModeChanged, mIsDeviceIdle=" + mIsDeviceIdle);
            }

            mIdleSinceNonInteractive = mIdleSinceNonInteractive || mIsDeviceIdle;
            updateActiveLocked();
        }
    }

    @GuardedBy("mLock")
    private void onEnabledLocked() {
        if (DEBUG) {
            Slog.d(TAG, "onEnabledLocked");
        }

        if (mPowerManager.isInteractive()) {
            onInteractive();
        } else {
            onNonInteractive();
        }

        registerBroadcastReceiver();
    }

    @GuardedBy("mLock")
    private void onDisabledLocked() {
        if (DEBUG) {
            Slog.d(TAG, "onDisabledLocked");
        }

        cancelStandbyTimeoutAlarmLocked();
        unregisterBroadcastReceiver();
        updateActiveLocked();
    }

    @VisibleForTesting
    void onSettingsChanged() {
        if (DEBUG) {
            Slog.d(TAG, "onSettingsChanged");
        }
        synchronized (mLock) {
            final boolean oldEnabled = mIsEnabled;
            updateSettingsLocked();

            if (mIsEnabled != oldEnabled) {
                if (mIsEnabled) {
                    onEnabledLocked();
                } else {
                    onDisabledLocked();
                }

                notifyEnabledChangedLocked();
            }
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    private void unregisterBroadcastReceiver() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    @GuardedBy("mLock")
    private void notifyEnabledChangedLocked() {
        if (DEBUG) {
            Slog.d(TAG, "notifyEnabledChangedLocked, mIsEnabled=" + mIsEnabled);
        }

        final Intent intent = new Intent(PowerManager.ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void onStandbyTimeoutExpired() {
        if (DEBUG) {
            Slog.d(TAG, "onStandbyTimeoutExpired");
        }
        synchronized (mLock) {
            updateActiveLocked();
        }
    }

    @GuardedBy("mLock")
    private void enqueueNotifyActiveChangedLocked() {
        final long now = mClock.elapsedRealtime();
        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_ACTIVE_CHANGED, mIsActive);
        mHandler.sendMessageAtTime(msg, now);
    }

    /** Notify other system components about the updated Low Power Standby active state */
    private void notifyActiveChanged(boolean active) {
        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        final NetworkPolicyManagerInternal npmi = LocalServices.getService(
                NetworkPolicyManagerInternal.class);

        pmi.setLowPowerStandbyActive(active);
        npmi.setLowPowerStandbyActive(active);
    }

    @VisibleForTesting
    boolean isActive() {
        synchronized (mLock) {
            return mIsActive;
        }
    }

    boolean isSupported() {
        synchronized (mLock) {
            return mSupportedConfig;
        }
    }

    boolean isEnabled() {
        synchronized (mLock) {
            return mSupportedConfig && mIsEnabled;
        }
    }

    void setEnabled(boolean enabled) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                Slog.w(TAG, "Low Power Standby cannot be enabled "
                        + "because it is not supported on this device");
                return;
            }

            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_STANDBY_ENABLED, enabled ? 1 : 0);
            onSettingsChanged();
        }
    }

    /** Set whether Low Power Standby should be active during doze maintenance mode. */
    @VisibleForTesting
    public void setActiveDuringMaintenance(boolean activeDuringMaintenance) {
        synchronized (mLock) {
            if (!mSupportedConfig) {
                Slog.w(TAG, "Low Power Standby settings cannot be changed "
                        + "because it is not supported on this device");
                return;
            }

            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE,
                    activeDuringMaintenance ? 1 : 0);
            onSettingsChanged();
        }
    }

    void forceActive(boolean active) {
        synchronized (mLock) {
            mForceActive = active;
            updateActiveLocked();
        }
    }

    void dump(PrintWriter pw) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        ipw.println();
        ipw.println("Low Power Standby Controller:");
        ipw.increaseIndent();
        synchronized (mLock) {
            ipw.print("mIsActive=");
            ipw.println(mIsActive);
            ipw.print("mIsEnabled=");
            ipw.println(mIsEnabled);
            ipw.print("mSupportedConfig=");
            ipw.println(mSupportedConfig);
            ipw.print("mEnabledByDefaultConfig=");
            ipw.println(mEnabledByDefaultConfig);
            ipw.print("mStandbyTimeoutConfig=");
            ipw.println(mStandbyTimeoutConfig);

            if (mIsActive || mIsEnabled) {
                ipw.print("mIsInteractive=");
                ipw.println(mIsInteractive);
                ipw.print("mLastInteractiveTime=");
                ipw.println(mLastInteractiveTimeElapsed);
                ipw.print("mIdleSinceNonInteractive=");
                ipw.println(mIdleSinceNonInteractive);
                ipw.print("mIsDeviceIdle=");
                ipw.println(mIsDeviceIdle);
            }

            final int[] allowlistUids = getAllowlistUidsLocked();
            ipw.print("mAllowlistUids=");
            ipw.println(Arrays.toString(allowlistUids));
        }
        ipw.decreaseIndent();
    }

    void dumpProto(ProtoOutputStream proto, long tag) {
        synchronized (mLock) {
            final long token = proto.start(tag);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ACTIVE, mIsActive);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ENABLED, mIsEnabled);
            proto.write(LowPowerStandbyControllerDumpProto.IS_SUPPORTED_CONFIG, mSupportedConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IS_ENABLED_BY_DEFAULT_CONFIG,
                    mEnabledByDefaultConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IS_INTERACTIVE, mIsInteractive);
            proto.write(LowPowerStandbyControllerDumpProto.LAST_INTERACTIVE_TIME,
                    mLastInteractiveTimeElapsed);
            proto.write(LowPowerStandbyControllerDumpProto.STANDBY_TIMEOUT_CONFIG,
                    mStandbyTimeoutConfig);
            proto.write(LowPowerStandbyControllerDumpProto.IDLE_SINCE_NON_INTERACTIVE,
                    mIdleSinceNonInteractive);
            proto.write(LowPowerStandbyControllerDumpProto.IS_DEVICE_IDLE, mIsDeviceIdle);

            final int[] allowlistUids = getAllowlistUidsLocked();
            for (int appId : allowlistUids) {
                proto.write(LowPowerStandbyControllerDumpProto.ALLOWLIST, appId);
            }

            proto.end(token);
        }
    }

    private class LowPowerStandbyHandler extends Handler {
        LowPowerStandbyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STANDBY_TIMEOUT:
                    onStandbyTimeoutExpired();
                    break;
                case MSG_NOTIFY_ACTIVE_CHANGED:
                    boolean active = (boolean) msg.obj;
                    notifyActiveChanged(active);
                    break;
                case MSG_NOTIFY_ALLOWLIST_CHANGED:
                    final int[] allowlistUids = (int[]) msg.obj;
                    notifyAllowlistChanged(allowlistUids);
                    break;
            }
        }
    }

    private void addToAllowlistInternal(int uid) {
        if (DEBUG) {
            Slog.i(TAG, "Adding to allowlist: " + uid);
        }
        synchronized (mLock) {
            if (mSupportedConfig && !mAllowlistUids.get(uid)) {
                mAllowlistUids.append(uid, true);
                enqueueNotifyAllowlistChangedLocked();
            }
        }
    }

    private void removeFromAllowlistInternal(int uid) {
        if (DEBUG) {
            Slog.i(TAG, "Removing from allowlist: " + uid);
        }
        synchronized (mLock) {
            if (mSupportedConfig && mAllowlistUids.get(uid)) {
                mAllowlistUids.delete(uid);
                enqueueNotifyAllowlistChangedLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private int[] getAllowlistUidsLocked() {
        final int[] uids = new int[mAllowlistUids.size()];
        for (int i = 0; i < mAllowlistUids.size(); i++) {
            uids[i] = mAllowlistUids.keyAt(i);
        }
        return uids;
    }

    @GuardedBy("mLock")
    private void enqueueNotifyAllowlistChangedLocked() {
        final long now = mClock.elapsedRealtime();
        final int[] allowlistUids = getAllowlistUidsLocked();
        final Message msg = mHandler.obtainMessage(MSG_NOTIFY_ALLOWLIST_CHANGED, allowlistUids);
        mHandler.sendMessageAtTime(msg, now);
    }

    private void notifyAllowlistChanged(int[] allowlistUids) {
        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        final NetworkPolicyManagerInternal npmi = LocalServices.getService(
                NetworkPolicyManagerInternal.class);
        pmi.setLowPowerStandbyAllowlist(allowlistUids);
        npmi.setLowPowerStandbyAllowlist(allowlistUids);
    }

    private final class LocalService extends LowPowerStandbyControllerInternal {
        @Override
        public void addToAllowlist(int uid) {
            addToAllowlistInternal(uid);
        }

        @Override
        public void removeFromAllowlist(int uid) {
            removeFromAllowlistInternal(uid);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            onSettingsChanged();
        }
    }
}
