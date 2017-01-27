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
package com.android.systemui.statusbar.policy;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.SubscriptionInfo;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.util.ArrayList;
import java.util.List;


/**
 * Implements network listeners and forwards the calls along onto other listeners but on
 * the current or specified Looper.
 */
public class CallbackHandler extends Handler implements EmergencyListener, SignalCallback {
    private static final int MSG_EMERGENCE_CHANGED           = 0;
    private static final int MSG_SUBS_CHANGED                = 1;
    private static final int MSG_NO_SIM_VISIBLE_CHANGED      = 2;
    private static final int MSG_ETHERNET_CHANGED            = 3;
    private static final int MSG_AIRPLANE_MODE_CHANGED       = 4;
    private static final int MSG_MOBILE_DATA_ENABLED_CHANGED = 5;
    private static final int MSG_ADD_REMOVE_EMERGENCY        = 6;
    private static final int MSG_ADD_REMOVE_SIGNAL           = 7;

    // All the callbacks.
    private final ArrayList<EmergencyListener> mEmergencyListeners = new ArrayList<>();
    private final ArrayList<SignalCallback> mSignalCallbacks = new ArrayList<>();

    public CallbackHandler() {
        super();
    }

    @VisibleForTesting
    CallbackHandler(Looper looper) {
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
                    signalCluster.setNoSims(msg.arg1 != 0);
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
    public void setWifiIndicators(final boolean enabled, final IconState statusIcon,
            final IconState qsIcon, final boolean activityIn, final boolean activityOut,
            final String description) {
        post(new Runnable() {
            @Override
            public void run() {
                for (SignalCallback callback : mSignalCallbacks) {
                    callback.setWifiIndicators(enabled, statusIcon, qsIcon, activityIn, activityOut,
                            description);
                }
            }
        });
    }

    @Override
    public void setMobileDataIndicators(final IconState statusIcon, final IconState qsIcon,
            final int statusType, final int qsType,final boolean activityIn,
            final boolean activityOut, final String typeContentDescription,
            final String description, final boolean isWide, final int subId, boolean roaming) {
        post(new Runnable() {
            @Override
            public void run() {
                for (SignalCallback signalCluster : mSignalCallbacks) {
                    signalCluster.setMobileDataIndicators(statusIcon, qsIcon, statusType, qsType,
                            activityIn, activityOut, typeContentDescription, description, isWide,
                            subId, roaming);
                }
            }
        });
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        obtainMessage(MSG_SUBS_CHANGED, subs).sendToTarget();
    }

    @Override
    public void setNoSims(boolean show) {
        obtainMessage(MSG_NO_SIM_VISIBLE_CHANGED, show ? 1 : 0, 0).sendToTarget();
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
        obtainMessage(MSG_ETHERNET_CHANGED, icon).sendToTarget();;
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        obtainMessage(MSG_AIRPLANE_MODE_CHANGED, icon).sendToTarget();;
    }

    public void setListening(EmergencyListener listener, boolean listening) {
        obtainMessage(MSG_ADD_REMOVE_EMERGENCY, listening ? 1 : 0, 0, listener).sendToTarget();
    }

    public void setListening(SignalCallback listener, boolean listening) {
        obtainMessage(MSG_ADD_REMOVE_SIGNAL, listening ? 1 : 0, 0, listener).sendToTarget();
    }

}
