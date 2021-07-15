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

package com.android.internal.telecom;

import android.telecom.BluetoothCallQualityReport;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCall;
import android.telephony.CallQuality;
import com.android.internal.telecom.ICallDiagnosticServiceAdapter;

/**
 * Internal remote interface for a call diagnostic service.
 * @see android.telecom.CallDiagnosticService
 * @hide
 */
oneway interface ICallDiagnosticService {
    void setAdapter(in ICallDiagnosticServiceAdapter adapter);
    void initializeDiagnosticCall(in ParcelableCall call);
    void updateCall(in ParcelableCall call);
    void updateCallAudioState(in CallAudioState callAudioState);
    void removeDiagnosticCall(in String callId);
    void receiveDeviceToDeviceMessage(in String callId, int message, int value);
    void callQualityChanged(in String callId, in CallQuality callQuality);
    void receiveBluetoothCallQualityReport(in BluetoothCallQualityReport qualityReport);
    void notifyCallDisconnected(in String callId, in DisconnectCause disconnectCause);
}
