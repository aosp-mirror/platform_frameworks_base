/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.connectivity;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.SubscriptionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.connectivity.NetworkController.EmergencyListener;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;


/**
 * Implements network listeners and forwards the calls along onto other listeners but on
 * the current or specified Looper.
 */
@SysUISingleton
public class CallbackHandler extends Handler implements EmergencyListener, SignalCallback {
    private static final String TAG = "CallbackHandler";
    private static final int MSG_EMERGENCE_CHANGED           = 0;
    private static final int MSG_SUBS_CHANGED                = 1;
    private static final int MSG_NO_SIM_VISIBLE_CHANGED      = 2;
    private static final int MSG_ETHERNET_CHANGED            = 3;
    private static final int MSG_AIRPLANE_MODE_CHANGED       = 4;
    private static final int MSG_MOBILE_DATA_ENABLED_CHANGED = 5;
    private static final int MSG_ADD_REMOVE_EMERGENCY        = 6;
    private static final int MSG_ADD_REMOVE_SIGNAL           = 7;
    private static final int HISTORY_SIZE = 64;
    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    // All the callbacks.
    private final ArrayList<EmergencyListener> mEmergencyListeners = new ArrayList<>();
    private final ArrayList<SignalCallback> mSignalCallbacks = new ArrayList<>();

    // Save the previous HISTORY_SIZE states for logging.
    private final String[] mHistory = new String[HISTORY_SIZE];
    // Where to copy the next state into.
    private int mHistoryIndex;
    private String mLastCallback;

    @Inject
    @VisibleForTesting
    CallbackHandler(@Main Looper looper) {
        super(looper);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_EMERGENCE_CHANGED:
                for (EmergencyListener listener : mEmergencyListeners) {
                    listener.setEmergencyCallsOnly(msg.arg1 != 0);
                }
                break;
            case MSG_SUBS_CHANGED:
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setSubs((List<SubscriptionInfo>) msg.obj);
                }
                break;
            case MSG_NO_SIM_VISIBLE_CHANGED:
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setNoSims(msg.arg1 != 0, msg.arg2 != 0);
                }
                break;
            case MSG_ETHERNET_CHANGED:
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setEthernetIndicators((IconState) msg.obj);
                }
                break;
            case MSG_AIRPLANE_MODE_CHANGED:
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setIsAirplaneMode((IconState) msg.obj);
                }
                break;
            case MSG_MOBILE_DATA_ENABLED_CHANGED:
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setMobileDataEnabled(msg.arg1 != 0);
                }
                break;
            case MSG_ADD_REMOVE_EMERGENCY:
                if (msg.arg1 != 0) {
                    mEmergencyListeners.add((EmergencyListener) msg.obj);
                } else {
                    mEmergencyListeners.remove((EmergencyListener) msg.obj);
                }
                break;
            case MSG_ADD_REMOVE_SIGNAL:
                if (msg.arg1 != 0) {
                    mSignalCallbacks.add((SignalCallback) msg.obj);
                } else {
                    mSignalCallbacks.remove((SignalCallback) msg.obj);
                }
                break;
        }
    }

    @Override
    public void setWifiIndicators(final WifiIndicators indicators) {
        String log = new StringBuilder()
                .append(SSDF.format(System.currentTimeMillis())).append(",")
                .append(indicators)
                .toString();
        recordLastCallback(log);
        post(() -> {
            for (SignalCallback callback : mSignalCallbacks) {
                callback.setWifiIndicators(indicators);
            }
        });
    }

    @Override
    public void setMobileDataIndicators(final MobileDataIndicators indicators) {
        String log = new StringBuilder()
                .append(SSDF.format(System.currentTimeMillis())).append(",")
                .append(indicators)
                .toString();
        recordLastCallback(log);
        post(() -> {
            for (SignalCallback signalCluster : mSignalCallbacks) {
                signalCluster.setMobileDataIndicators(indicators);
            }
        });
    }

    @Override
    public void setConnectivityStatus(boolean noDefaultNetwork, boolean noValidatedNetwork,
                boolean noNetworksAvailable) {
        String currentCallback = new StringBuilder()
                .append("setConnectivityStatus: ")
                .append("noDefaultNetwork=").append(noDefaultNetwork).append(",")
                .append("noValidatedNetwork=").append(noValidatedNetwork).append(",")
                .append("noNetworksAvailable=").append(noNetworksAvailable)
                .toString();
        if (!currentCallback.equals(mLastCallback)) {
            mLastCallback = currentCallback;
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append(currentCallback).append(",")
                    .toString();
            recordLastCallback(log);
        }
        post(() -> {
            for (SignalCallback signalCluster : mSignalCallbacks) {
                signalCluster.setConnectivityStatus(
                        noDefaultNetwork, noValidatedNetwork, noNetworksAvailable);
            }
        });
    }

    @Override
    public void setCallIndicator(IconState statusIcon, int subId) {
        String currentCallback = new StringBuilder()
                .append("setCallIndicator: ")
                .append("statusIcon=").append(statusIcon).append(",")
                .append("subId=").append(subId)
                .toString();
        if (!currentCallback.equals(mLastCallback)) {
            mLastCallback = currentCallback;
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append(currentCallback).append(",")
                    .toString();
            recordLastCallback(log);
        }
        post(() -> {
            for (SignalCallback signalCluster : mSignalCallbacks) {
                signalCluster.setCallIndicator(statusIcon, subId);
            }
        });
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        String currentCallback = new StringBuilder()
                .append("setSubs: ")
                .append("subs=").append(subs == null ? "" : subs.toString())
                .toString();
        if (!currentCallback.equals(mLastCallback)) {
            mLastCallback = currentCallback;
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append(currentCallback).append(",")
                    .toString();
            recordLastCallback(log);
        }
        obtainMessage(MSG_SUBS_CHANGED, subs).sendToTarget();
    }

    @Override
    public void setNoSims(boolean show, boolean simDetected) {
        obtainMessage(MSG_NO_SIM_VISIBLE_CHANGED, show ? 1 : 0, simDetected ? 1 : 0).sendToTarget();
    }

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        obtainMessage(MSG_MOBILE_DATA_ENABLED_CHANGED, enabled ? 1 : 0, 0).sendToTarget();
    }

    @Override
    public void setEmergencyCallsOnly(boolean emergencyOnly) {
        obtainMessage(MSG_EMERGENCE_CHANGED, emergencyOnly ? 1 : 0, 0).sendToTarget();
    }

    @Override
    public void setEthernetIndicators(IconState icon) {
        String log = new StringBuilder()
                .append(SSDF.format(System.currentTimeMillis())).append(",")
                .append("setEthernetIndicators: ")
                .append("icon=").append(icon)
                .toString();
        recordLastCallback(log);
        obtainMessage(MSG_ETHERNET_CHANGED, icon).sendToTarget();
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        String currentCallback = new StringBuilder()
                .append("setIsAirplaneMode: ")
                .append("icon=").append(icon)
                .toString();
        if (!currentCallback.equals(mLastCallback)) {
            mLastCallback = currentCallback;
            String log = new StringBuilder()
                    .append(SSDF.format(System.currentTimeMillis())).append(",")
                    .append(currentCallback).append(",")
                    .toString();
            recordLastCallback(log);
        }
        obtainMessage(MSG_AIRPLANE_MODE_CHANGED, icon).sendToTarget();
    }

    void setListening(EmergencyListener listener, boolean listening) {
        obtainMessage(MSG_ADD_REMOVE_EMERGENCY, listening ? 1 : 0, 0, listener).sendToTarget();
    }

    void setListening(SignalCallback listener, boolean listening) {
        obtainMessage(MSG_ADD_REMOVE_SIGNAL, listening ? 1 : 0, 0, listener).sendToTarget();
    }

    protected void recordLastCallback(String callback) {
        mHistory[mHistoryIndex] = callback;
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
    }

    /**
     * Dump the Callback logs
     */
    public void dump(PrintWriter pw) {
        pw.println("  - CallbackHandler -----");
        int size = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            if (mHistory[i] != null) {
                size++;
            }
        }
        // Print out the previous states in ordered number.
        for (int i = mHistoryIndex + HISTORY_SIZE - 1;
                i >= mHistoryIndex + HISTORY_SIZE - size; i--) {
            pw.println("  Previous Callback(" + (mHistoryIndex + HISTORY_SIZE - i) + "): "
                    + mHistory[i & (HISTORY_SIZE - 1)]);
        }
    }

}
