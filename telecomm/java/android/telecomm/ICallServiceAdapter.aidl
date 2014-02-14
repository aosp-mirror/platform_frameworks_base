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

package android.telecomm;

import android.telecomm.CallInfo;

/**
 * Provides methods for ICallService implementations to interact with the system phone app.
 * TODO(santoscordon): Need final public-facing comments in this file.
 */
oneway interface ICallServiceAdapter {

    /**
     * Receives confirmation of a call service's ability to place a call. This method is used in
     * response to {@link ICallService#isCompatibleWith}.
     * TODO(santoscordon): rename to setIsCompatibleWith().
     *
     * @param callId The identifier of the call for which compatibility is being received. This ID
     *     should correspond to the ID given as part of the call information in
     *     {@link ICallService#isCompatibleWith}.
     * @param isCompatible True if the call service can place the call.
     */
    void setCompatibleWith(String callId, boolean isCompatible);

    /**
     * Receives confirmation of the existence of an incoming call connected through the call
     * service. Invoked by the call service after it receives a confirmation request from Telecomm
     * through {@link ICallService#confirmIncomingCall}. The call info object must contain all the
     * updated status of the call and use the same call ID as was passed into
     * {@link ICallService#confirmIncomingCall}.
     *
     * @param callInfo The details of the relevant call.
     */
    void handleConfirmedIncomingCall(in CallInfo callInfo);

    /**
     * Tells Telecomm that an attempt to place the specified outgoing call succeeded.
     * TODO(santoscordon): Consider adding a CallState parameter in case this outgoing call is
     * somehow no longer in the DIALING state.
     *
     * @param callId The ID of the outgoing call.
     */
    void handleSuccessfulOutgoingCall(String callId);

    /**
     * Tells Telecomm that an attempt to place the specified outgoing call failed.
     *
     * @param callId The ID of the outgoing call.
     * @param errorMessage The error associated with the failed call attempt.
     */
    void handleFailedOutgoingCall(String callId, String errorMessage);

    /**
     * Sets a call's state to active (e.g., an ongoing call where two parties can actively
     * communicate).
     *
     * @param callId The unique ID of the call whose state is changing to active.
     */
    void setActive(String callId);

    /**
     * Sets a call's state to ringing (e.g., an inbound ringing call).
     *
     * @param callId The unique ID of the call whose state is changing to ringing.
     */
    void setRinging(String callId);

    /**
     * Sets a call's state to dialing (e.g., dialing an outbound call).
     *
     * @param callId The unique ID of the call whose state is changing to dialing.
     */
    void setDialing(String callId);

    /**
     * Sets a call's state to disconnected.
     *
     * @param callId The unique ID of the call whose state is changing to disconnected.
     */
    void setDisconnected(String callId);
}
