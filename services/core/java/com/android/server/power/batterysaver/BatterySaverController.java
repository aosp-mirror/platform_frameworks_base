/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.power.V1_0.PowerHint;
import android.os.BatteryManager;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerManagerInternal.LowPowerModeListener;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.power.PowerManagerService;
import com.android.server.power.batterysaver.BatterySaverPolicy.BatterySaverPolicyListener;
import com.android.server.power.batterysaver.BatterySaverPolicy.Policy;
import com.android.server.power.batterysaver.BatterySavingStats.BatterySaverState;
import com.android.server.power.batterysaver.BatterySavingStats.DozeState;
import com.android.server.power.batterysaver.BatterySavingStats.InteractiveState;

import java.util.ArrayList;

/**
 * Responsible for battery saver mode transition logic.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held. (Settings provider is okay.)
 */
public class BatterySaverController implements BatterySaverPolicyListener {
    static final String TAG = "BatterySaverController";

    static final boolean DEBUG = BatterySaverPolicy.DEBUG;

    private final Object mLock;
    private final Context mContext;
    private final MyHandler mHandler;
    private final FileUpdater mFileUpdater;

    private PowerManager mPowerManager;

    private final BatterySaverPolicy mBatterySaverPolicy;

    private final BatterySavingStats mBatterySavingStats;

    @GuardedBy("mLock")
    private final ArrayList<LowPowerModeListener> mListeners = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mFullEnabled;

    @GuardedBy("mLock")
    private boolean mAdaptiveEnabled;

    @GuardedBy("mLock")
    private boolean mIsPluggedIn;

    /**
     * Whether full was previously enabled or not; only for the event logging. Only use it from
     * {@link #handleBatterySaverStateChanged}.
     */
    private boolean mFullPreviouslyEnabled;

    /**
     * Whether adaptive was previously enabled or not; only for the event logging. Only use it from
     * {@link #handleBatterySaverStateChanged}.
     */
    private boolean mAdaptivePreviouslyEnabled;

    @GuardedBy("mLock")
    private boolean mIsInteractive;

    /**
     * Read-only list of plugins. No need for synchronization.
     */
    private final Plugin[] mPlugins;

    public static final int REASON_PERCENTAGE_AUTOMATIC_ON = 0;
    public static final int REASON_PERCENTAGE_AUTOMATIC_OFF = 1;
    public static final int REASON_MANUAL_ON = 2;
    public static final int REASON_MANUAL_OFF = 3;
    public static final int REASON_STICKY_RESTORE = 4;
    public static final int REASON_INTERACTIVE_CHANGED = 5;
    public static final int REASON_POLICY_CHANGED = 6;
    public static final int REASON_PLUGGED_IN = 7;
    public static final int REASON_SETTING_CHANGED = 8;
    public static final int REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_ON = 9;
    public static final int REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_OFF = 10;
    public static final int REASON_STICKY_RESTORE_OFF = 11;
    public static final int REASON_ADAPTIVE_DYNAMIC_POWER_SAVINGS_CHANGED = 12;
    public static final int REASON_TIMEOUT = 13;

    /**
     * Plugin interface. All methods are guaranteed to be called on the same (handler) thread.
     */
    public interface Plugin {
        void onSystemReady(BatterySaverController caller);

        void onBatterySaverChanged(BatterySaverController caller);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "onReceive: " + intent);
            }
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_SCREEN_OFF:
                    if (!isPolicyEnabled()) {
                        updateBatterySavingStats();
                        return; // No need to send it if not enabled.
                    }
                    // Don't send the broadcast, because we never did so in this case.
                    mHandler.postStateChanged(/*sendBroadcast=*/ false,
                            REASON_INTERACTIVE_CHANGED);
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    synchronized (mLock) {
                        mIsPluggedIn = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                    }
                    // Fall-through.
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                case PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED:
                    updateBatterySavingStats();
                    break;
            }
        }
    };

    /**
     * Constructor.
     */
    public BatterySaverController(Object lock, Context context, Looper looper,
            BatterySaverPolicy policy, BatterySavingStats batterySavingStats) {
        mLock = lock;
        mContext = context;
        mHandler = new MyHandler(looper);
        mBatterySaverPolicy = policy;
        mBatterySaverPolicy.addListener(this);
        mFileUpdater = new FileUpdater(context);
        mBatterySavingStats = batterySavingStats;

        // Initialize plugins.
        mPlugins = new Plugin[] {
                new BatterySaverLocationPlugin(mContext)
        };
    }

    /**
     * Add a listener.
     */
    public void addListener(LowPowerModeListener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    /**
     * Called by {@link PowerManagerService} on system ready, *with no lock held*.
     */
    public void systemReady() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        mFileUpdater.systemReady(LocalServices.getService(ActivityManagerInternal.class)
                .isRuntimeRestarted());
        mHandler.postSystemReady();
    }

    private PowerManager getPowerManager() {
        if (mPowerManager == null) {
            mPowerManager =
                    Preconditions.checkNotNull(mContext.getSystemService(PowerManager.class));
        }
        return mPowerManager;
    }

    @Override
    public void onBatterySaverPolicyChanged(BatterySaverPolicy policy) {
        if (!isPolicyEnabled()) {
            return; // No need to send it if not enabled.
        }
        mHandler.postStateChanged(/*sendBroadcast=*/ true, REASON_POLICY_CHANGED);
    }

    private class MyHandler extends Handler {
        private static final int MSG_STATE_CHANGED = 1;

        private static final int ARG_DONT_SEND_BROADCAST = 0;
        private static final int ARG_SEND_BROADCAST = 1;

        private static final int MSG_SYSTEM_READY = 2;

        public MyHandler(Looper looper) {
            super(looper);
        }

        void postStateChanged(boolean sendBroadcast, int reason) {
            obtainMessage(MSG_STATE_CHANGED, sendBroadcast ?
                    ARG_SEND_BROADCAST : ARG_DONT_SEND_BROADCAST, reason).sendToTarget();
        }

        public void postSystemReady() {
            obtainMessage(MSG_SYSTEM_READY, 0, 0).sendToTarget();
        }

        @Override
        public void dispatchMessage(Message msg) {
            switch (msg.what) {
                case MSG_STATE_CHANGED:
                    handleBatterySaverStateChanged(
                            msg.arg1 == ARG_SEND_BROADCAST,
                            msg.arg2);
                    break;

                case MSG_SYSTEM_READY:
                    for (Plugin p : mPlugins) {
                        p.onSystemReady(BatterySaverController.this);
                    }
                    break;
            }
        }
    }

    /** Enable or disable full battery saver. */
    @VisibleForTesting
    public void enableBatterySaver(boolean enable, int reason) {
        synchronized (mLock) {
            if (mFullEnabled == enable) {
                return;
            }
            mFullEnabled = enable;

            if (updatePolicyLevelLocked()) {
                mHandler.postStateChanged(/*sendBroadcast=*/ true, reason);
            }
        }
    }

    private boolean updatePolicyLevelLocked() {
        if (mFullEnabled) {
            return mBatterySaverPolicy.setPolicyLevel(BatterySaverPolicy.POLICY_LEVEL_FULL);
        } else if (mAdaptiveEnabled) {
            return mBatterySaverPolicy.setPolicyLevel(BatterySaverPolicy.POLICY_LEVEL_ADAPTIVE);
        } else {
            return mBatterySaverPolicy.setPolicyLevel(BatterySaverPolicy.POLICY_LEVEL_OFF);
        }
    }

    /**
     * @return whether battery saver is enabled or not. This takes into
     * account whether a policy says to advertise isEnabled so this can be propagated externally.
     */
    public boolean isEnabled() {
        synchronized (mLock) {
            return mFullEnabled
                    || (mAdaptiveEnabled && mBatterySaverPolicy.shouldAdvertiseIsEnabled());
        }
    }

    /**
     * @return whether battery saver policy is enabled or not. This does not take into account
     * whether a policy says to advertise isEnabled, so this shouldn't be propagated externally.
     */
    private boolean isPolicyEnabled() {
        synchronized (mLock) {
            return mFullEnabled || mAdaptiveEnabled;
        }
    }

    boolean isFullEnabled() {
        synchronized (mLock) {
            return mFullEnabled;
        }
    }

    boolean isAdaptiveEnabled() {
        synchronized (mLock) {
            return mAdaptiveEnabled;
        }
    }

    boolean setAdaptivePolicyLocked(String settings, String deviceSpecificSettings, int reason) {
        return setAdaptivePolicyLocked(
                BatterySaverPolicy.Policy.fromSettings(settings, deviceSpecificSettings),
                reason);
    }

    boolean setAdaptivePolicyLocked(BatterySaverPolicyConfig config, int reason) {
        return setAdaptivePolicyLocked(BatterySaverPolicy.Policy.fromConfig(config), reason);
    }

    boolean setAdaptivePolicyLocked(Policy policy, int reason) {
        if (mBatterySaverPolicy.setAdaptivePolicyLocked(policy)) {
            mHandler.postStateChanged(/*sendBroadcast=*/ true, reason);
            return true;
        }
        return false;
    }

    boolean resetAdaptivePolicyLocked(int reason) {
        if (mBatterySaverPolicy.resetAdaptivePolicyLocked()) {
            mHandler.postStateChanged(/*sendBroadcast=*/ true, reason);
            return true;
        }
        return false;
    }

    boolean setAdaptivePolicyEnabledLocked(boolean enabled, int reason) {
        if (mAdaptiveEnabled == enabled) {
            return false;
        }
        mAdaptiveEnabled = enabled;
        if (updatePolicyLevelLocked()) {
            mHandler.postStateChanged(/*sendBroadcast=*/ true, reason);
            return true;
        }
        return false;
    }

    /** @return whether device is in interactive state. */
    public boolean isInteractive() {
        synchronized (mLock) {
            return mIsInteractive;
        }
    }

    /** @return Battery saver policy. */
    public BatterySaverPolicy getBatterySaverPolicy() {
        return mBatterySaverPolicy;
    }

    /**
     * @return true if launch boost should currently be disabled.
     */
    public boolean isLaunchBoostDisabled() {
        return isPolicyEnabled() && mBatterySaverPolicy.isLaunchBoostDisabled();
    }

    /**
     * Dispatch power save events to the listeners.
     *
     * This method is always called on the handler thread.
     *
     * This method is called only in the following cases:
     * - When battery saver becomes activated.
     * - When battery saver becomes deactivated.
     * - When battery saver is on and the interactive state changes.
     * - When battery saver is on and the battery saver policy changes.
     * - When adaptive battery saver becomes activated.
     * - When adaptive battery saver becomes deactivated.
     * - When adaptive battery saver is active (and full is off) and the policy changes.
     */
    void handleBatterySaverStateChanged(boolean sendBroadcast, int reason) {
        final LowPowerModeListener[] listeners;

        final boolean enabled;
        final boolean isInteractive = getPowerManager().isInteractive();
        final ArrayMap<String, String> fileValues;

        synchronized (mLock) {
            enabled = mFullEnabled || mAdaptiveEnabled;

            EventLogTags.writeBatterySaverMode(
                    mFullPreviouslyEnabled ? 1 : 0, // Previously off or on.
                    mAdaptivePreviouslyEnabled ? 1 : 0, // Previously off or on.
                    mFullEnabled ? 1 : 0, // Now off or on.
                    mAdaptiveEnabled ? 1 : 0, // Now off or on.
                    isInteractive ?  1 : 0, // Device interactive state.
                    enabled ? mBatterySaverPolicy.toEventLogString() : "",
                    reason);

            mFullPreviouslyEnabled = mFullEnabled;
            mAdaptivePreviouslyEnabled = mAdaptiveEnabled;

            listeners = mListeners.toArray(new LowPowerModeListener[0]);

            mIsInteractive = isInteractive;

            if (enabled) {
                fileValues = mBatterySaverPolicy.getFileValues(isInteractive);
            } else {
                fileValues = null;
            }
        }

        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.powerHint(PowerHint.LOW_POWER, isEnabled() ? 1 : 0);
        }

        updateBatterySavingStats();

        if (ArrayUtils.isEmpty(fileValues)) {
            mFileUpdater.restoreDefault();
        } else {
            mFileUpdater.writeFiles(fileValues);
        }

        for (Plugin p : mPlugins) {
            p.onBatterySaverChanged(this);
        }

        if (sendBroadcast) {

            if (DEBUG) {
                Slog.i(TAG, "Sending broadcasts for mode: " + isEnabled());
            }

            // Send the broadcasts and notify the listeners. We only do this when the battery saver
            // mode changes, but not when only the screen state changes.
            Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING)
                    .putExtra(PowerManager.EXTRA_POWER_SAVE_MODE, isEnabled())
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

            intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

            // Send internal version that requires signature permission.
            intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED_INTERNAL);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    Manifest.permission.DEVICE_POWER);

            for (LowPowerModeListener listener : listeners) {
                final PowerSaveState result =
                        mBatterySaverPolicy.getBatterySaverPolicy(listener.getServiceType());
                listener.onLowPowerModeChanged(result);
            }
        }
    }

    private void updateBatterySavingStats() {
        final PowerManager pm = getPowerManager();
        if (pm == null) {
            Slog.wtf(TAG, "PowerManager not initialized");
            return;
        }
        final boolean isInteractive = pm.isInteractive();
        final int dozeMode =
                pm.isDeviceIdleMode() ? DozeState.DEEP
                        : pm.isLightDeviceIdleMode() ? DozeState.LIGHT
                        : DozeState.NOT_DOZING;

        synchronized (mLock) {
            if (mIsPluggedIn) {
                mBatterySavingStats.startCharging();
                return;
            }
            mBatterySavingStats.transitionState(
                    mFullEnabled ? BatterySaverState.ON :
                            (mAdaptiveEnabled ? BatterySaverState.ADAPTIVE : BatterySaverState.OFF),
                    isInteractive ? InteractiveState.INTERACTIVE : InteractiveState.NON_INTERACTIVE,
                    dozeMode);
        }
    }
}
