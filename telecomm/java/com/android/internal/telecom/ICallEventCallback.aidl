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
import com.android.internal.telecom.ICallControl;
import android.os.ResultReceiver;
import android.telecom.CallAudioState;
import android.telecom.CallException;

/**
 * {@hide}
 */
oneway interface ICallEventCallback {
    // publicly exposed. Client should override
    void onAddCallControl(String callId, int resultCode, in ICallControl callControl,
     in CallException exception);
    void onSetActive(String callId, in ResultReceiver callback);
    void onSetInactive(String callId, in ResultReceiver callback);
    void onAnswer(String callId, int videoState, in ResultReceiver callback);
    void onReject(String callId, in ResultReceiver callback);
    void onDisconnect(String callId, in ResultReceiver callback);
    void onCallAudioStateChanged(String callId, in CallAudioState callAudioState);
    // hidden methods that help with cleanup
    void removeCallFromTransactionalServiceWrapper(String callId);
}