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
 * @hide
 */
oneway interface ICallServiceAdapter {

    /**
     * Retrieves a new unique call ID for use with newIncomingCall.
     */
    void getNextCallId(/* TODO(santoscordon): Needs response object */);

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
     * Tells CallsManager of a new incoming call.
     *
     * @param handle The handle to the other party or null if no such handle is available (as with
     *     blocked caller ID).
     * @param callId The unique ID (via {@link #getNextCallId}) of the new incoming call.
     */
    void newIncomingCall(String handle, String callId);

    /**
     * Tells CallsManager of a new outgoing call. Use of this method should always follow
     * {@link ICallService#call}.
     *
     * @param callId The unique ID (via {@link ICallService#call}) of the new outgoing call.
     */
    void newOutgoingCall(String callId);

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
