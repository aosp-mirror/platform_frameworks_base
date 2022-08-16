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

package com.android.server.am;

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.RemoteException;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.app.IBatteryStats;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Class for receiving data connection state to report to {@link BatteryStatsService}. */
public class DataConnectionStats extends BroadcastReceiver {
    private static final String TAG = "DataConnectionStats";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final Handler mListenerHandler;
    private final PhoneStateListener mPhoneStateListener;

    private int mSimState = TelephonyManager.SIM_STATE_READY;
    private SignalStrength mSignalStrength;
    private ServiceState mServiceState;
    private int mDataState = TelephonyManager.DATA_DISCONNECTED;
    private int mNrState = NetworkRegistrationInfo.NR_STATE_NONE;

    public DataConnectionStats(Context context, Handler listenerHandler) {
        mContext = context;
        mBatteryStats = BatteryStatsService.getService();
        mListenerHandler = listenerHandler;
        mPhoneStateListener =
                new PhoneStateListenerImpl(new PhoneStateListenerExecutor(listenerHandler));
    }

    /** Start data connection state monitoring. */
    public void startMonitoring() {
        TelephonyManager phone = mContext.getSystemService(TelephonyManager.class);
        phone.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE
                | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                | PhoneStateListener.LISTEN_DATA_ACTIVITY);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(this, filter, null /* broadcastPermission */, mListenerHandler);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_SIM_STATE_CHANGED)) {
            updateSimState(intent);
            notePhoneDataConnectionState();
        }
    }

    private void notePhoneDataConnectionState() {
        if (mServiceState == null) {
            return;
        }
        boolean simReadyOrUnknown = mSimState == TelephonyManager.SIM_STATE_READY
                || mSimState == TelephonyManager.SIM_STATE_UNKNOWN;
        boolean visible = (simReadyOrUnknown || isCdma()) // we only check the sim state for GSM
                && hasService()
                && mDataState == TelephonyManager.DATA_CONNECTED;
        NetworkRegistrationInfo regInfo =
                mServiceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN);
        int networkType = regInfo == null ? TelephonyManager.NETWORK_TYPE_UNKNOWN
                : regInfo.getAccessNetworkTechnology();
        // If the device is in NSA NR connection the networkType will report as LTE.
        // For cell dwell rate metrics, this should report NR instead.
        if (mNrState == NetworkRegistrationInfo.NR_STATE_CONNECTED) {
            networkType = TelephonyManager.NETWORK_TYPE_NR;
        }
        if (DEBUG) {
            Log.d(TAG, String.format("Noting data connection for network type %s: %svisible",
                    networkType, visible ? "" : "not "));
        }
        try {
            mBatteryStats.notePhoneDataConnectionState(networkType, visible,
                    mServiceState.getState(), mServiceState.getNrFrequencyRange());
        } catch (RemoteException e) {
            Log.w(TAG, "Error noting data connection state", e);
        }
    }

    private void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(Intent.EXTRA_SIM_STATE);
        if (Intent.SIM_STATE_ABSENT.equals(stateExtra)) {
            mSimState = TelephonyManager.SIM_STATE_ABSENT;
        } else if (Intent.SIM_STATE_READY.equals(stateExtra)) {
            mSimState = TelephonyManager.SIM_STATE_READY;
        } else if (Intent.SIM_STATE_LOCKED.equals(stateExtra)) {
            final String lockedReason =
                    intent.getStringExtra(Intent.EXTRA_SIM_LOCKED_REASON);
            if (Intent.SIM_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = TelephonyManager.SIM_STATE_PIN_REQUIRED;
            } else if (Intent.SIM_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = TelephonyManager.SIM_STATE_PUK_REQUIRED;
            } else {
                mSimState = TelephonyManager.SIM_STATE_NETWORK_LOCKED;
            }
        } else {
            mSimState = TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    private boolean isCdma() {
        return mSignalStrength != null && !mSignalStrength.isGsm();
    }

    private boolean hasService() {
        return mServiceState != null
                && mServiceState.getState() != ServiceState.STATE_OUT_OF_SERVICE
                && mServiceState.getState() != ServiceState.STATE_POWER_OFF;
    }

    private static class PhoneStateListenerExecutor implements Executor {
        @NonNull
        private final Handler mHandler;

        PhoneStateListenerExecutor(@NonNull Handler handler) {
            mHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mHandler.post(command)) {
                throw new RejectedExecutionException(mHandler + " is shutting down");
            }
        }
    }

    private class PhoneStateListenerImpl extends PhoneStateListener {
        PhoneStateListenerImpl(Executor executor) {
            super(executor);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mSignalStrength = signalStrength;
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            mServiceState = state;
            mNrState = state.getNrState();
            notePhoneDataConnectionState();
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            mDataState = state;
            notePhoneDataConnectionState();
        }

        @Override
        public void onDataActivity(int direction) {
            notePhoneDataConnectionState();
        }
    };
}
