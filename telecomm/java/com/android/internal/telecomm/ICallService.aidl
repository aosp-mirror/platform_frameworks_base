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
import android.telecomm.CallInfo;

import com.android.internal.telecomm.ICallServiceAdapter;

/**
 * Service interface for services which would like to provide calls to be
 * managed by the system in-call UI.
 *
 * This interface provides methods that the android framework can use to deliver commands
 * for calls provided by this call service including making new calls and disconnecting
 * existing ones. A binding to ICallService implementations exists for two conditions:
 * 1) There exists one or more live calls for that call service,
 * 2) Prior to an outbound call to test if this call service is compatible with the outgoing call.
 *
 * TODO(santoscordon): Need final public-facing comments in this file.
 * {@hide}
 */
oneway interface ICallService {

    /**
     * Sets an implementation of ICallServiceAdapter which the call service can use to add new calls
     * and communicate state changes of existing calls. This is the first method that is called
     * after the framework binds to the call service.
     *
     * @param callServiceAdapter Interface to CallsManager for adding and updating calls.
     */
    void setCallServiceAdapter(in ICallServiceAdapter callServiceAdapter);

    /**
     * Determines if the ICallService can place the specified call. Response is sent via
     * {@link ICallServiceAdapter#setCompatibleWith}.  When responding, the correct call ID must be
     * specified. It is expected that the call service respond within 500 milliseconds. Any response
     * that takes longer than 500 milliseconds will be treated as being incompatible.
     * TODO(santoscordon): 500 ms was arbitrarily chosen and must be confirmed before this
     * API is made public.  Only used in the context of outgoing calls and call switching (handoff).
     *
     * @param callInfo The details of the relevant call.
     */
    void isCompatibleWith(in CallInfo callInfo);

    /**
     * Attempts to call the relevant party using the specified call's handle, be it a phone number,
     * SIP address, or some other kind of user ID.  Note that the set of handle types is
     * dynamically extensible since call providers should be able to implement arbitrary
     * handle-calling systems.  See {@link #isCompatibleWith}. It is expected that the
     * call service respond via {@link ICallServiceAdapter#handleSuccessfulOutgoingCall} if it can
     * successfully make the call.  Only used in the context of outgoing calls.
     * TODO(santoscordon): Figure out how a call service can short-circuit a failure to the adapter.
     *
     * @param callInfo The details of the relevant call.
     */
    void call(in CallInfo callInfo);

    /**
     * Aborts the outgoing call attempt. Invoked in the unlikely event that Telecomm decides to
     * abort an attempt to place a call.  Only ever be invoked after {@link #call} invocations.
     * After this is invoked, Telecomm does not expect any more updates about the call and will
     * actively ignore any such update. This is different from {@link #disconnect} where Telecomm
     * expects confirmation via ICallServiceAdapter.markCallAsDisconnected.
     *
     * @param callId The identifier of the call to abort.
     */
    void abort(String callId);

    /**
     * Receives a new call ID to use with an incoming call. Invoked by Telecomm after it is notified
     * that this call service has a pending incoming call, see
     * {@link TelecommConstants#ACTION_INCOMING_CALL}. The call service must first give Telecomm
     * additional information of the call through {@link ICallServiceAdapter#handleIncomingCall}.
     * Following that, the call service can update the call at will using the specified call ID.
     *
     * As part of the {@link TelecommConstants#ACTION_INCOMING_CALL} Intent, a  Bundle of extra
     * data could be sent via {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}, which is
     * returned through this method. If no data was given, an empty Bundle will be returned.
     *
     * @param callId The ID of the call.
     * @param extras The Bundle of extra information passed via
     *     {@link TelecommConstants#EXTRA_INCOMING_CALL_EXTRAS}.
     */
    void setIncomingCallId(String callId, in Bundle extras);

    /**
     * Answers a ringing call identified by callId. Telecomm invokes this method as a result of the
     * user hitting the "answer" button in the incoming call screen.
     *
     * @param callId The ID of the call.
     */
    void answer(String callId);

    /**
     * Rejects a ringing call identified by callId. Telecomm invokes this method as a result of the
     * user hitting the "reject" button in the incoming call screen.
     *
     * @param callId The ID of the call.
     */
    void reject(String callId);

    /**
     * Disconnects the call identified by callId.  Used for outgoing and incoming calls.
     *
     * @param callId The identifier of the call to disconnect.
     */
    void disconnect(String callId);

    /**
     * Puts the call identified by callId on hold. Telecomm invokes this method when a call should
     * be placed on hold per user request or because a different call was made active.
     *
     * @param callId The identifier of the call to put on hold.
     */
    void hold(String callId);

    /**
     * Removes the call identified by callId from hold. Telecomm invokes this method when a call
     * should be removed on hold per user request or because a different call was put on hold.
     *
     * @param callId The identifier of the call to remove from hold.
     */
    void unhold(String callId);
}
