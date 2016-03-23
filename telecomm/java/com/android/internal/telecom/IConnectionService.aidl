/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.telecom.CallAudioState;
import android.telecom.ConnectionRequest;
import android.telecom.PhoneAccountHandle;

import com.android.internal.telecom.IConnectionServiceAdapter;

/**
 * Internal remote interface for connection services.
 *
 * @see android.telecom.ConnectionService
 *
 * @hide
 */
oneway interface IConnectionService {
    void addConnectionServiceAdapter(in IConnectionServiceAdapter adapter);

    void removeConnectionServiceAdapter(in IConnectionServiceAdapter adapter);

    void createConnection(
            in PhoneAccountHandle connectionManagerPhoneAccount,
            String callId,
            in ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown);

    void abort(String callId);

    void answerVideo(String callId, int videoState);

    void answer(String callId);

    void reject(String callId);

    void rejectWithMessage(String callId, String message);

    void disconnect(String callId);

    void silence(String callId);

    void hold(String callId);

    void unhold(String callId);

    void onCallAudioStateChanged(String activeCallId, in CallAudioState callAudioState);

    void playDtmfTone(String callId, char digit);

    void stopDtmfTone(String callId);

    void conference(String conferenceCallId, String callId);

    void splitFromConference(String callId);

    void mergeConference(String conferenceCallId);

    void swapConference(String conferenceCallId);

    void onPostDialContinue(String callId, boolean proceed);

    void pullExternalCall(String callId);

    void sendCallEvent(String callId, String event, in Bundle extras);

    void onExtrasChanged(String callId, in Bundle extras);
}
