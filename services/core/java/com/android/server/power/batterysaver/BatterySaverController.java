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
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.power.BatterySaverPolicy;
import com.android.server.power.BatterySaverPolicy.BatterySaverPolicyListener;
import com.android.server.power.PowerManagerService;

import java.util.ArrayList;

/**
 * Responsible for battery saver mode transition logic.
 */
public class BatterySaverController implements BatterySaverPolicyListener {
    static final String TAG = "BatterySaverController";

    static final boolean DEBUG = BatterySaverPolicy.DEBUG;

    private final Object mLock = new Object();
    private final Context mContext;
    private final MyHandler mHandler;
    private final FileUpdater mFileUpdater;

    private PowerManager mPowerManager;

    private final BatterySaverPolicy mBatterySaverPolicy;

    @GuardedBy("mLock")
    private final ArrayList<LowPowerModeListener> mListeners = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mEnabled;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_SCREEN_OFF:
                    if (!isEnabled()) {
                        return; // No need to send it if not enabled.
                    }
                    // Don't send the broadcast, because we never did so in this case.
                    mHandler.postStateChanged(/*sendBroadcast=*/ false);
                    break;
            }
        }
    };

    /**
     * Constructor.
     */
    public BatterySaverController(Context context, Looper looper, BatterySaverPolicy policy) {
        mContext = context;
        mHandler = new MyHandler(looper);
        mBatterySaverPolicy = policy;
        mBatterySaverPolicy.addListener(this);
        mFileUpdater = new FileUpdater(context);
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
     * Called by {@link PowerManagerService} on system ready..
     */
    public void systemReady() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mReceiver, filter);

        mFileUpdater.systemReady(LocalServices.getService(ActivityManagerInternal.class)
                .isRuntimeRestarted());
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
        mHandler.postStateChanged(/*sendBroadcast=*/ true);
    }

    private class MyHandler extends Handler {
        private static final int MSG_STATE_CHANGED = 1;

        private static final int ARG_DONT_SEND_BROADCAST = 0;
        private static final int ARG_SEND_BROADCAST = 1;

        public MyHandler(Looper looper) {
            super(looper);
        }

        public void postStateChanged(boolean sendBroadcast) {
            obtainMessage(MSG_STATE_CHANGED, sendBroadcast ?
                    ARG_SEND_BROADCAST : ARG_DONT_SEND_BROADCAST, 0).sendToTarget();
        }

        @Override
        public void dispatchMessage(Message msg) {
            switch (msg.what) {
                case MSG_STATE_CHANGED:
                    handleBatterySaverStateChanged(msg.arg1 == ARG_SEND_BROADCAST);
                    break;
            }
        }
    }

    /**
     * Called by {@link PowerManagerService} to update the battery saver stete.
     */
    public void enableBatterySaver(boolean enable) {
        synchronized (mLock) {
            if (mEnabled == enable) {
                return;
            }
            mEnabled = enable;

            mHandler.postStateChanged(/*sendBroadcast=*/ true);
        }
    }

    /** @return whether battery saver is enabled or not. */
    boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
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
    void handleBatterySaverStateChanged(boolean sendBroadcast) {
        final LowPowerModeListener[] listeners;

        final boolean enabled;
        final boolean isInteractive = getPowerManager().isInteractive();
        final ArrayMap<String, String> fileValues;

        synchronized (mLock) {
            Slog.i(TAG, "Battery saver " + (mEnabled ? "enabled" : "disabled")
                    + ": isInteractive=" + isInteractive);

            listeners = mListeners.toArray(new LowPowerModeListener[mListeners.size()]);
            enabled = mEnabled;

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

        if (ArrayUtils.isEmpty(fileValues)) {
            mFileUpdater.restoreDefault();
        } else {
            mFileUpdater.writeFiles(fileValues);
        }

        if (sendBroadcast) {
            if (enabled) {
                // STOPSHIP Remove the toast.
                Toast.makeText(mContext,
                        com.android.internal.R.string.battery_saver_warning,
                        Toast.LENGTH_LONG).show();
            }

            if (DEBUG) {
                Slog.i(TAG, "Sending broadcasts for mode: " + enabled);
            }

            // Send the broadcasts and notify the listeners. We only do this when the battery saver
            // mode changes, but not when only the screen state changes.
            Intent intent = new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGING)
                    .putExtra(PowerManager.EXTRA_POWER_SAVE_MODE, enabled)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcast(intent);

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
}
