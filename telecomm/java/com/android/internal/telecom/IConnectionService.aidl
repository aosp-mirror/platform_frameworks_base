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

import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.telecom.CallAudioState;
import android.telecom.ConnectionRequest;
import android.telecom.Logging.Session;
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
    void addConnectionServiceAdapter(in IConnectionServiceAdapter adapter,
    in Session.Info sessionInfo);

    void removeConnectionServiceAdapter(in IConnectionServiceAdapter adapter,
    in Session.Info sessionInfo);

    void createConnection(
            in PhoneAccountHandle connectionManagerPhoneAccount,
            String callId,
            in ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown,
            in Session.Info sessionInfo);

    void createConnectionComplete(String callId, in Session.Info sessionInfo);

    void createConnectionFailed(in PhoneAccountHandle connectionManagerPhoneAccount, String callId,
            in ConnectionRequest request, boolean isIncoming, in Session.Info sessionInfo);

    void createConference(
            in PhoneAccountHandle connectionManagerPhoneAccount,
            String callId,
            in ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown,
            in Session.Info sessionInfo);

    void createConferenceComplete(String callId, in Session.Info sessionInfo);

    void createConferenceFailed(in PhoneAccountHandle connectionManagerPhoneAccount, String callId,
            in ConnectionRequest request, boolean isIncoming, in Session.Info sessionInfo);


    void abort(String callId, in Session.Info sessionInfo);

    void answerVideo(String callId, int videoState, in Session.Info sessionInfo);

    void answer(String callId, in Session.Info sessionInfo);

    void deflect(String callId, in Uri address, in Session.Info sessionInfo);

    void reject(String callId, in Session.Info sessionInfo);

    void rejectWithReason(String callId, int rejectReason, in Session.Info sessionInfo);

    void rejectWithMessage(String callId, String message, in Session.Info sessionInfo);

    void transfer(String callId, in Uri number, boolean isConfirmationRequired,
            in Session.Info sessionInfo);

    void consultativeTransfer(String callId, String otherCallId, in Session.Info sessionInfo);

    void disconnect(String callId, in Session.Info sessionInfo);

    void silence(String callId, in Session.Info sessionInfo);

    void hold(String callId, in Session.Info sessionInfo);

    void unhold(String callId, in Session.Info sessionInfo);

    void onCallAudioStateChanged(String activeCallId, in CallAudioState callAudioState,
    in Session.Info sessionInfo);

    void playDtmfTone(String callId, char digit, in Session.Info sessionInfo);

    void stopDtmfTone(String callId, in Session.Info sessionInfo);

    void conference(String conferenceCallId, String callId, in Session.Info sessionInfo);

    void splitFromConference(String callId, in Session.Info sessionInfo);

    void mergeConference(String conferenceCallId, in Session.Info sessionInfo);

    void swapConference(String conferenceCallId, in Session.Info sessionInfo);

    void addConferenceParticipants(String CallId, in List<Uri> participants,
    in Session.Info sessionInfo);

    void onPostDialContinue(String callId, boolean proceed, in Session.Info sessionInfo);

    void pullExternalCall(String callId, in Session.Info sessionInfo);

    void sendCallEvent(String callId, String event, in Bundle extras, in Session.Info sessionInfo);

    void onExtrasChanged(String callId, in Bundle extras, in Session.Info sessionInfo);

    void startRtt(String callId, in ParcelFileDescriptor fromInCall,
    in ParcelFileDescriptor toInCall, in Session.Info sessionInfo);

    void stopRtt(String callId, in Session.Info sessionInfo);

    void respondToRttUpgradeRequest(String callId, in ParcelFileDescriptor fromInCall,
    in ParcelFileDescriptor toInCall, in Session.Info sessionInfo);

    void connectionServiceFocusLost(in Session.Info sessionInfo);

    void connectionServiceFocusGained(in Session.Info sessionInfo);

    void handoverFailed(String callId, in ConnectionRequest request,
            int error, in Session.Info sessionInfo);

    void handoverComplete(String callId, in Session.Info sessionInfo);
}
