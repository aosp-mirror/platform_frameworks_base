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

import android.telecomm.CallInfo;

/**
 * Internal remote callback interface for call services.
 *
 * @see android.telecomm.CallServiceAdapter
 *
 * {@hide}
 */
oneway interface ICallServiceAdapter {
    void setIsCompatibleWith(String callId, boolean isCompatible);

    void notifyIncomingCall(in CallInfo callInfo);

    void handleSuccessfulOutgoingCall(String callId);

    void handleFailedOutgoingCall(String callId, String errorMessage);

    void setActive(String callId);

    void setRinging(String callId);

    void setDialing(String callId);

    void setDisconnected(String callId, int disconnectCause, String disconnectMessage);

    void setOnHold(String callId);

    void setRequestingRingback(String callId, boolean ringing);

    void setCanConferenceWith(String callId, in List<String> conferenceCapableCallIds);

    void setIsConferenced(String conferenceCallId, String callId, boolean isConferenced);

    void removeCall(String callId);
}
