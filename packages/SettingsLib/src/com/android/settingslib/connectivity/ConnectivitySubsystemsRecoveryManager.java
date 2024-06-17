/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.SubsystemRestartTrackingCallback;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

/**
 * An interface class to manage connectivity subsystem recovery/restart operations.
 */
public class ConnectivitySubsystemsRecoveryManager {
    private static final String TAG = "ConnectivitySubsystemsRecoveryManager";

    private final Context mContext;
    private final Handler mHandler;
    private RecoveryAvailableListener mRecoveryAvailableListener = null;

    private static final long RESTART_TIMEOUT_MS = 15_000; // 15 seconds

    private WifiManager mWifiManager = null;
    private TelephonyManager mTelephonyManager = null;
    private final BroadcastReceiver mApmMonitor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            RecoveryAvailableListener listener = mRecoveryAvailableListener;
            if (listener != null) {
                listener.onRecoveryAvailableChangeListener(isRecoveryAvailable());
            }
        }
    };
    private boolean mApmMonitorRegistered = false;
    private boolean mWifiRestartInProgress = false;
    private boolean mTelephonyRestartInProgress = false;
    private RecoveryStatusCallback mCurrentRecoveryCallback = null;
    private final SubsystemRestartTrackingCallback mWifiSubsystemRestartTrackingCallback =
            new SubsystemRestartTrackingCallback() {
        @Override
        public void onSubsystemRestarting() {
            // going to do nothing on this - already assuming that subsystem is restarting
        }

        @Override
        public void onSubsystemRestarted() {
            mWifiRestartInProgress = false;
            stopTrackingWifiRestart();
            checkIfAllSubsystemsRestartsAreDone();
        }
    };
    private final MobileTelephonyCallback mTelephonyCallback = new MobileTelephonyCallback();

    private class MobileTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.RadioPowerStateListener {
        @Override
        public void onRadioPowerStateChanged(int state) {
            if (!mTelephonyRestartInProgress || mCurrentRecoveryCallback == null) {
                stopTrackingTelephonyRestart();
            }

            if (state == TelephonyManager.RADIO_POWER_ON) {
                mTelephonyRestartInProgress = false;
                stopTrackingTelephonyRestart();
                checkIfAllSubsystemsRestartsAreDone();
            }
        }
    }

    public ConnectivitySubsystemsRecoveryManager(@NonNull Context context,
            @NonNull Handler handler) {
        mContext = context;
        mHandler = new Handler(handler.getLooper());

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            mWifiManager = mContext.getSystemService(WifiManager.class);
            if (mWifiManager == null) {
                Log.e(TAG, "WifiManager not available!?");
            }
        }

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
            if (mTelephonyManager == null) {
                Log.e(TAG, "TelephonyManager not available!?");
            }
        }
    }

    /**
     * A listener which indicates to the caller whether a recovery operation is available across
     * the specified technologies.
     *
     * Set using {@link #setRecoveryAvailableListener(RecoveryAvailableListener)}, cleared
     * using {@link #clearRecoveryAvailableListener()}.
     */
    public interface RecoveryAvailableListener {
        /**
         * Called whenever the recovery availability status changes.
         *
         * @param isAvailable True if recovery is available across ANY of the requested
         *                    technologies, false if recovery is not available across ALL of the
         *                    requested technologies.
         */
        void onRecoveryAvailableChangeListener(boolean isAvailable);
    }

    /**
     * Set a {@link RecoveryAvailableListener} to listen to changes in the recovery availability
     * operation for the specified technology(ies).
     *
     * @param listener Listener to be triggered
     */
    public void setRecoveryAvailableListener(@NonNull RecoveryAvailableListener listener) {
        mHandler.post(() -> {
            mRecoveryAvailableListener = listener;
            startTrackingRecoveryAvailability();
        });
    }

    /**
     * Clear a listener set with
     * {@link #setRecoveryAvailableListener(RecoveryAvailableListener)}.
     */
    public void clearRecoveryAvailableListener() {
        mHandler.post(() -> {
            mRecoveryAvailableListener = null;
            stopTrackingRecoveryAvailability();
        });
    }

    private boolean isApmEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private boolean isWifiEnabled() {
        // TODO: this doesn't consider the scan-only mode. I.e. WiFi is "disabled" while location
        // mode is enabled. Probably need to reset WiFi in that state as well. Though this may
        // appear strange to the user in that they've actually disabled WiFi.
        return mWifiManager != null && (mWifiManager.isWifiEnabled()
                || mWifiManager.isWifiApEnabled());
    }

    /**
     * Provide an indication as to whether subsystem recovery is "available" - i.e. will be
     * executed if triggered via {@link #triggerSubsystemRestart(String, RecoveryStatusCallback)}.
     *
     * @return true if a subsystem recovery is available, false otherwise.
     */
    public boolean isRecoveryAvailable() {
        if (!isApmEnabled()) return true;

        // even if APM is enabled we may still have recovery potential if WiFi is enabled
        return isWifiEnabled();
    }

    private void startTrackingRecoveryAvailability() {
        if (mApmMonitorRegistered) return;

        mContext.registerReceiver(mApmMonitor,
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), null, mHandler);
        mApmMonitorRegistered = true;
    }

    private void stopTrackingRecoveryAvailability() {
        if (!mApmMonitorRegistered) return;

        mContext.unregisterReceiver(mApmMonitor);
        mApmMonitorRegistered = false;
    }

    @VisibleForTesting
    void startTrackingWifiRestart() {
        if (mWifiManager == null) return;
        mWifiManager.registerSubsystemRestartTrackingCallback(new HandlerExecutor(mHandler),
                mWifiSubsystemRestartTrackingCallback);
    }

    @VisibleForTesting
    void stopTrackingWifiRestart() {
        if (mWifiManager == null) return;
        mWifiManager.unregisterSubsystemRestartTrackingCallback(
                mWifiSubsystemRestartTrackingCallback);
    }

    @VisibleForTesting
    void startTrackingTelephonyRestart() {
        if (mTelephonyManager == null) return;
        mTelephonyManager.registerTelephonyCallback(new HandlerExecutor(mHandler),
                mTelephonyCallback);
    }

    @VisibleForTesting
    void stopTrackingTelephonyRestart() {
        if (mTelephonyManager == null) return;
        mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
    }

    private void checkIfAllSubsystemsRestartsAreDone() {
        if (!mWifiRestartInProgress && !mTelephonyRestartInProgress
                && mCurrentRecoveryCallback != null) {
            mCurrentRecoveryCallback.onSubsystemRestartOperationEnd();
            mCurrentRecoveryCallback = null;
        }
    }

    /**
     * Callbacks used with
     * {@link #triggerSubsystemRestart(String, RecoveryStatusCallback)} to get
     * information about when recovery starts and is completed.
     */
    public interface RecoveryStatusCallback {
        /**
         * Callback for a subsystem restart triggered via
         * {@link #triggerSubsystemRestart(String, RecoveryStatusCallback)} - indicates
         * that operation has started.
         */
        void onSubsystemRestartOperationBegin();

        /**
         * Callback for a subsystem restart triggered via
         * {@link #triggerSubsystemRestart(String, RecoveryStatusCallback)} - indicates
         * that operation has ended. Note that subsystems may still take some time to come up to
         * full functionality.
         */
        void onSubsystemRestartOperationEnd();
    }

    /**
     * Trigger connectivity recovery for all requested technologies.
     *
     * @param reason   An optional reason code to pass through to the technology-specific
     *                 API. May be used to trigger a bug report.
     * @param callback Callbacks triggered when recovery status changes.
     */
    public void triggerSubsystemRestart(String reason, @NonNull RecoveryStatusCallback callback) {
        // TODO: b/183530649 : clean-up or make use of the `reason` argument
        mHandler.post(() -> {
            boolean someSubsystemRestarted = false;

            if (mWifiRestartInProgress) {
                Log.e(TAG, "Wifi restart still in progress");
                return;
            }

            if (mTelephonyRestartInProgress) {
                Log.e(TAG, "Telephony restart still in progress");
                return;
            }

            if (isWifiEnabled()) {
                mWifiManager.restartWifiSubsystem();
                mWifiRestartInProgress = true;
                someSubsystemRestarted = true;
                startTrackingWifiRestart();
            }

            if (mTelephonyManager != null && !isApmEnabled()) {
                if (mTelephonyManager.rebootRadio()) {
                    mTelephonyRestartInProgress = true;
                    someSubsystemRestarted = true;
                    startTrackingTelephonyRestart();
                }
            }

            if (someSubsystemRestarted) {
                mCurrentRecoveryCallback = callback;
                callback.onSubsystemRestartOperationBegin();

                mHandler.postDelayed(() -> {
                    stopTrackingWifiRestart();
                    stopTrackingTelephonyRestart();
                    mWifiRestartInProgress = false;
                    mTelephonyRestartInProgress = false;
                    if (mCurrentRecoveryCallback != null) {
                        mCurrentRecoveryCallback.onSubsystemRestartOperationEnd();
                        mCurrentRecoveryCallback = null;
                    }
                }, RESTART_TIMEOUT_MS);
            }
        });
    }
}

