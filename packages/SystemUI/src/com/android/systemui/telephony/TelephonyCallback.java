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

package com.android.systemui.telephony;

import android.telephony.ServiceState;
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import android.telephony.TelephonyCallback.CallStateListener;
import android.telephony.TelephonyCallback.ServiceStateListener;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Class for use by {@link TelephonyListenerManager} to centralize TelephonyManager Callbacks.
 *
 * There are more callback interfaces defined in {@link android.telephony.TelephonyCallback} that
 * are not currently covered. Add them here if they ever become necessary.
 */
class TelephonyCallback extends android.telephony.TelephonyCallback
        implements ActiveDataSubscriptionIdListener, CallStateListener, ServiceStateListener {

    private final List<ActiveDataSubscriptionIdListener> mActiveDataSubscriptionIdListeners =
            new ArrayList<>();
    private final List<CallStateListener> mCallStateListeners = new ArrayList<>();
    private final List<ServiceStateListener> mServiceStateListeners = new ArrayList<>();

    @Inject
    TelephonyCallback() {
    }

    boolean hasAnyListeners() {
        return !mActiveDataSubscriptionIdListeners.isEmpty()
                || !mCallStateListeners.isEmpty()
                || !mServiceStateListeners.isEmpty();
    }

    @Override
    public void onActiveDataSubscriptionIdChanged(int subId) {
        List<ActiveDataSubscriptionIdListener> listeners;
        synchronized (mActiveDataSubscriptionIdListeners) {
            listeners = new ArrayList<>(mActiveDataSubscriptionIdListeners);
        }
        listeners.forEach(listener -> listener.onActiveDataSubscriptionIdChanged(subId));
    }

    void addActiveDataSubscriptionIdListener(ActiveDataSubscriptionIdListener listener) {
        mActiveDataSubscriptionIdListeners.add(listener);
    }

    void removeActiveDataSubscriptionIdListener(ActiveDataSubscriptionIdListener listener) {
        mActiveDataSubscriptionIdListeners.remove(listener);
    }

    @Override
    public void onCallStateChanged(int state) {
        List<CallStateListener> listeners;
        synchronized (mCallStateListeners) {
            listeners = new ArrayList<>(mCallStateListeners);
        }
        listeners.forEach(listener -> listener.onCallStateChanged(state));
    }

    void addCallStateListener(CallStateListener listener) {
        mCallStateListeners.add(listener);
    }

    void removeCallStateListener(CallStateListener listener) {
        mCallStateListeners.remove(listener);
    }

    @Override
    public void onServiceStateChanged(@NonNull ServiceState serviceState) {
        List<ServiceStateListener> listeners;
        synchronized (mServiceStateListeners) {
            listeners = new ArrayList<>(mServiceStateListeners);
        }
        listeners.forEach(listener -> listener.onServiceStateChanged(serviceState));
    }

    void addServiceStateListener(ServiceStateListener listener) {
        mServiceStateListeners.add(listener);
    }

    void removeServiceStateListener(ServiceStateListener listener) {
        mServiceStateListeners.remove(listener);
    }
}
