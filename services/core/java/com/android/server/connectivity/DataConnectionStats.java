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

package com.android.server.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.server.am.BatteryStatsService;

public class DataConnectionStats extends BroadcastReceiver {
    private static final String TAG = "DataConnectionStats";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;

    private IccCardConstants.State mSimState = IccCardConstants.State.READY;
    private SignalStrength mSignalStrength;
    private ServiceState mServiceState;
    private int mDataState = TelephonyManager.DATA_DISCONNECTED;

    public DataConnectionStats(Context context) {
        mContext = context;
        mBatteryStats = BatteryStatsService.getService();
    }

    public void startMonitoring() {
        TelephonyManager phone =
                (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        phone.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE
              | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
              | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
              | PhoneStateListener.LISTEN_DATA_ACTIVITY);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        mContext.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            updateSimState(intent);
            notePhoneDataConnectionState();
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            notePhoneDataConnectionState();
       }
    }

    private void notePhoneDataConnectionState() {
        if (mServiceState == null) {
            return;
        }
        boolean simReadyOrUnknown = mSimState == IccCardConstants.State.READY
                || mSimState == IccCardConstants.State.UNKNOWN;
        boolean visible = (simReadyOrUnknown || isCdma()) // we only check the sim state for GSM
                && hasService()
                && mDataState == TelephonyManager.DATA_CONNECTED;
        int networkType = mServiceState.getDataNetworkType();
        if (DEBUG) Log.d(TAG, String.format("Noting data connection for network type %s: %svisible",
                networkType, visible ? "" : "not "));
        try {
            mBatteryStats.notePhoneDataConnectionState(networkType, visible,
                    mServiceState.getState());
        } catch (RemoteException e) {
            Log.w(TAG, "Error noting data connection state", e);
        }
    }

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            mSimState = IccCardConstants.State.ABSENT;
        } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            mSimState = IccCardConstants.State.READY;
        } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason =
                    intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
            if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PIN_REQUIRED;
            } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PUK_REQUIRED;
            } else {
                mSimState = IccCardConstants.State.NETWORK_LOCKED;
            }
        } else {
            mSimState = IccCardConstants.State.UNKNOWN;
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

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mSignalStrength = signalStrength;
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            mServiceState = state;
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
