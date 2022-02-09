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
import android.telecom.PhoneAccountHandle;

/**
 * Internal remote callback interface for in-call services.
 *
 * @see android.telecom.InCallAdapter
 *
 * {@hide}
 */
oneway interface IInCallAdapter {
    void answerCall(String callId, int videoState);

    void deflectCall(String callId, in Uri address);

    void rejectCall(String callId, boolean rejectWithMessage, String textMessage);

    void rejectCallWithReason(String callId, int rejectReason);

    void transferCall(String callId, in Uri targetNumber, boolean isConfirmationRequired);

    void consultativeTransfer(String callId, String otherCallId);

    void disconnectCall(String callId);

    void holdCall(String callId);

    void unholdCall(String callId);

    void mute(boolean shouldMute);

    void setAudioRoute(int route, String bluetoothAddress);

    void enterBackgroundAudioProcessing(String callId);

    void exitBackgroundAudioProcessing(String callId, boolean shouldRing);

    void playDtmfTone(String callId, char digit);

    void stopDtmfTone(String callId);

    void postDialContinue(String callId, boolean proceed);

    void phoneAccountSelected(String callId, in PhoneAccountHandle accountHandle,
            boolean setDefault);

    void conference(String callId, String otherCallId);

    void splitFromConference(String callId);

    void mergeConference(String callId);

    void swapConference(String callId);

    void addConferenceParticipants(String callId, in List<Uri> participants);

    void turnOnProximitySensor();

    void turnOffProximitySensor(boolean screenOnImmediately);

    void pullExternalCall(String callId);

    void sendCallEvent(String callId, String event, int targetSdkVer, in Bundle extras);

    void putExtras(String callId, in Bundle extras);

    void removeExtras(String callId, in List<String> keys);

    void sendRttRequest(String callId);

    void respondToRttRequest(String callId, int id, boolean accept);

    void stopRtt(String callId);

    void setRttMode(String callId, int mode);

    void handoverTo(String callId, in PhoneAccountHandle destAcct, int videoState,
            in Bundle extras);
}
