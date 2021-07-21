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

import android.telecom.CallAudioState;
import android.telecom.ParcelableCall;

/**
 * Remote interface for messages from the CallDiagnosticService to the platform.
 * @see android.telecom.CallDiagnosticService
 * @hide
 */
oneway interface ICallDiagnosticServiceAdapter {
    void displayDiagnosticMessage(in String callId, int messageId, in CharSequence message);
    void clearDiagnosticMessage(in String callId, int messageId);
    void sendDeviceToDeviceMessage(in String callId, int message, int value);
    void overrideDisconnectMessage(in String callId, in CharSequence message);
}
