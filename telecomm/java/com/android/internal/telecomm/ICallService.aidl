/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.Bundle;
import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;

import com.android.internal.telecomm.ICallServiceAdapter;

/**
 * Internal remote interface for call services.
 *
 * @see android.telecomm.CallService
 *
 * @hide
 */
oneway interface ICallService {
    void setCallServiceAdapter(in ICallServiceAdapter callServiceAdapter);

    void isCompatibleWith(in CallInfo callInfo);

    void call(in CallInfo callInfo);

    void abort(String callId);

    void setIncomingCallId(String callId, in Bundle extras);

    void answer(String callId);

    void reject(String callId);

    void disconnect(String callId);

    void hold(String callId);

    void unhold(String callId);

    void onAudioStateChanged(String activeCallId, in CallAudioState audioState);

    void playDtmfTone(String callId, char digit);

    void stopDtmfTone(String callId);
}
