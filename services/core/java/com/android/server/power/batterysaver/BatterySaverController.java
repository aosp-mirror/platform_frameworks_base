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
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.power.BatterySaverPolicy;
import com.android.server.power.BatterySaverPolicy.BatterySaverPolicyListener;
import com.android.server.power.PowerManagerService;
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
    private boolean mEnabled;

    @GuardedBy("mLock")
    private boolean mIsPluggedIn;

    /**
     * Previously enabled or not; only for the event logging. Only use it from
     * {@link #handleBatterySaverStateChanged}.
     */
    private boolean mPreviouslyEnabled;

    @GuardedBy("mLock")
    private boolean mIsInteractive;

    /**
     * Read-only list of plugins. No need for synchronization.
     */
    private final Plugin[] mPlugins;

    public static final int REASON_AUTOMATIC_ON = 0;
    public static final int REASON_AUTOMATIC_OFF = 1;
    public static final int REASON_MANUAL_ON = 2;
    public static final int REASON_MANUAL_OFF = 3;
    public static final int REASON_STICKY_RESTORE = 4;
    public static final int REASON_INTERACTIVE_CHANGED = 5;
    public static final int REASON_POLICY_CHANGED = 6;
    public static final int REASON_PLUGGED_IN = 7;
    public static final int REASON_SETTING_CHANGED = 8;

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
                    if (!isEnabled()) {
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
        final ArrayList<Plugin> plugins = new ArrayList<>();
        plugins.add(new BatterySaverLocationPlugin(mContext));

        mPlugins = plugins.toArray(new Plugin[plugins.size()]);
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
        if (!isEnabled()) {
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

        public void postStateChanged(boolean sendBroadcast, int reason) {
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

    /**
     * Called by {@link PowerManagerService} to update the battery saver stete.
     */
    public void enableBatterySaver(boolean enable, int reason) {
        synchronized (mLock) {
            if (mEnabled == enable) {
                return;
            }
            mEnabled = enable;

            mHandler.postStateChanged(/*sendBroadcast=*/ true, reason);
        }
    }

    /** @return whether battery saver is enabled or not. */
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
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
        return isEnabled() && mBatterySaverPolicy.isLaunchBoostDisabled();
    }

    /**
     * Dispatch power save events to the listeners.
     *
     * This method is always called on the handler thread.
     *
     * This method is called only in the following cases:
     * - When battery saver becomes activated.
     * - When battery saver becomes deactivated.
     * - When battery saver is on the interactive state changes.
     * - When battery saver is on the battery saver policy changes.
     */
    void handleBatterySaverStateChanged(boolean sendBroadcast, int reason) {
        final LowPowerModeListener[] listeners;

        final boolean enabled;
        final boolean isInteractive = getPowerManager().isInteractive();
        final ArrayMap<String, String> fileValues;

        synchronized (mLock) {
            EventLogTags.writeBatterySaverMode(
                    mPreviouslyEnabled ? 1 : 0, // Previously off or on.
                    mEnabled ? 1 : 0, // Now off or on.
                    isInteractive ?  1 : 0, // Device interactive state.
                    mEnabled ? mBatterySaverPolicy.toEventLogString() : "",
                    reason);
            mPreviouslyEnabled = mEnabled;

            listeners = mListeners.toArray(new LowPowerModeListener[mListeners.size()]);

            enabled = mEnabled;
            mIsInteractive = isInteractive;

            if (enabled) {
                fileValues = mBatterySaverPolicy.getFileValues(isInteractive);
            } else {
                fileValues = null;
            }
        }

        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.powerHint(PowerHint.LOW_POWER, enabled ? 1 : 0);
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
                Slog.i(TAG, "Sending broadcasts for mode: " + enabled);
            }

            // Send the broadcasts and notify the listeners. We only do this when the battery saver
            // mode changes, but not when only the screen state changes.
            Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING)
                    .putExtra(PowerManager.EXTRA_POWER_SAVE_MODE, enabled)
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
                        mBatterySaverPolicy.getBatterySaverPolicy(
                                listener.getServiceType(), enabled);
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
                    mEnabled ? BatterySaverState.ON : BatterySaverState.OFF,
                    isInteractive ? InteractiveState.INTERACTIVE : InteractiveState.NON_INTERACTIVE,
                    dozeMode);
        }
    }
}
