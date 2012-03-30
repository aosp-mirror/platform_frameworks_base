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

import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.CellInfo;

oneway interface IPhoneStateListener {
    void onServiceStateChanged(in ServiceState serviceState);
    void onSignalStrengthChanged(int asu);
    void onMessageWaitingIndicatorChanged(boolean mwi);
    void onCallForwardingIndicatorChanged(boolean cfi);

    // we use bundle here instead of CellLocation so it can get the right subclass
    void onCellLocationChanged(in Bundle location);
    void onCallStateChanged(int state, String incomingNumber);
    void onDataConnectionStateChanged(int state, int networkType);
    void onDataActivity(int direction);
    void onSignalStrengthsChanged(in SignalStrength signalStrength);
    void onOtaspChanged(in int otaspMode);
    void onCellInfoChanged(in CellInfo cellInfo);
}

