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

package com.android.internal.telecomm;

import android.telecomm.PhoneAccountHandle;

/**
 * Internal remote callback interface for in-call services.
 *
 * @see android.telecomm.InCallAdapter
 *
 * {@hide}
 */
oneway interface IInCallAdapter {
    void answerCall(String callId, int videoState);

    void rejectCall(String callId, boolean rejectWithMessage, String textMessage);

    void disconnectCall(String callId);

    void holdCall(String callId);

    void unholdCall(String callId);

    void mute(boolean shouldMute);

    void setAudioRoute(int route);

    void playDtmfTone(String callId, char digit);

    void stopDtmfTone(String callId);

    void postDialContinue(String callId, boolean proceed);

    void phoneAccountClicked(String callId);

    void phoneAccountSelected(String callId, in PhoneAccountHandle accountHandle);

    void conference(String callId, String otherCallId);

    void splitFromConference(String callId);

    void turnOnProximitySensor();

    void turnOffProximitySensor(boolean screenOnImmediately);
}
