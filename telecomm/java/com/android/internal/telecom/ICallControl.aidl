/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.telecom.CallControl;
import android.telecom.CallEndpoint;
import android.telecom.DisconnectCause;
import android.os.ResultReceiver;

/**
 * {@hide}
 */
oneway interface ICallControl {
    void setActive(String callId, in ResultReceiver callback);
    void setInactive(String callId, in ResultReceiver callback);
    void disconnect(String callId, in DisconnectCause disconnectCause, in ResultReceiver callback);
    void rejectCall(String callId, in ResultReceiver callback);
    void startCallStreaming(String callId, in ResultReceiver callback);
    void requestCallEndpointChange(in CallEndpoint callEndpoint, in ResultReceiver callback);
}