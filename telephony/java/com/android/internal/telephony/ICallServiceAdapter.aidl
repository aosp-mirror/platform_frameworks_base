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

package com.android.internal.telephony;

import com.android.internal.telephony.CallInfo;

/**
 * Provides methods for ICallService implementations to interact with the system phone app.
 */
oneway interface ICallServiceAdapter {

    /**
     * Retrieves a new unique call id for use with newOutgoingCall and newIncomingCall.
     */
    void getNextCallId(/* TODO(santoscordon): Needs response object */);

    /**
     * Tells CallsManager of a new incoming call.
     */
    void newIncomingCall(String callId, in CallInfo info);

    /**
     * Tells CallsManager of a new outgoing call.
     */
    void newOutgoingCall(String callId, in CallInfo info);

    /**
     * Sets a call's state to active (e.g., an ongoing call where two parties can actively
     * communicate).
     */
    void setActive(String callId);

    /**
     * Sets a call's state to ringing (e.g., an inbound ringing call).
     */
    void setRinging(String callId);

    /**
     * Sets a call's state to dialing (e.g., dialing an outbound call).
     */
    void setDialing(String callId);

    /**
     * Sets a call's state to disconnected.
     */
    void setDisconnected(String callId);
}
