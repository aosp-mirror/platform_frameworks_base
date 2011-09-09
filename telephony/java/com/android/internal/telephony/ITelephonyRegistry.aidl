/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Intent;
import android.net.LinkProperties;
import android.net.LinkCapabilities;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import com.android.internal.telephony.IPhoneStateListener;

interface ITelephonyRegistry {
    void listen(String pkg, IPhoneStateListener callback, int events, boolean notifyNow);

    void notifyCallState(int state, String incomingNumber);
    void notifyServiceState(in ServiceState state);
    void notifySignalStrength(in SignalStrength signalStrength);
    void notifyMessageWaitingChanged(boolean mwi);
    void notifyCallForwardingChanged(boolean cfi);
    void notifyDataActivity(int state);
    void notifyDataConnection(int state, boolean isDataConnectivityPossible,
            String reason, String apn, String apnType, in LinkProperties linkProperties,
            in LinkCapabilities linkCapabilities, int networkType, boolean roaming);
    void notifyDataConnectionFailed(String reason, String apnType);
    void notifyCellLocation(in Bundle cellLocation);
    void notifyOtaspChanged(in int otaspMode);
}
