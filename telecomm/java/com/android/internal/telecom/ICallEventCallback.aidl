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

import android.os.Bundle;
import android.telecom.CallControl;
import android.telecom.CallEndpoint;
import com.android.internal.telecom.ICallControl;
import android.os.ResultReceiver;
import android.telecom.CallAudioState;
import android.telecom.CallException;
import android.telecom.DisconnectCause;
import java.util.List;

/**
 * {@hide}
 */
oneway interface ICallEventCallback {
    // publicly exposed. Client should override
    void onAddCallControl(String callId, int resultCode, in ICallControl callControl,
     in CallException exception);
    // -- Call Event Actions / Call State Transitions
    void onSetActive(String callId, in ResultReceiver callback);
    void onSetInactive(String callId, in ResultReceiver callback);
    void onAnswer(String callId, int videoState, in ResultReceiver callback);
    void onDisconnect(String callId, in DisconnectCause cause, in ResultReceiver callback);
    // -- Streaming related. Client registered call streaming capabilities should override
    void onCallStreamingStarted(String callId, in ResultReceiver callback);
    void onCallStreamingFailed(String callId, int reason);
    // -- Audio related.
    void onCallEndpointChanged(String callId, in CallEndpoint endpoint);
    void onAvailableCallEndpointsChanged(String callId, in List<CallEndpoint> endpoint);
    void onMuteStateChanged(String callId, boolean isMuted);
    // -- Video Related
    void onVideoStateChanged(String callId, int videoState);
    // -- Events
    void onEvent(String callId, String event, in Bundle extras);
    // hidden methods that help with cleanup
    void removeCallFromTransactionalServiceWrapper(String callId);
}