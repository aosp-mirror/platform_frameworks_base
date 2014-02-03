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
import android.telecomm.ICallServiceAdapter;

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
 */
oneway interface ICallService {

    /**
     * Sets an implementation of ICallServiceAdapter which the call service can use to add new calls
     * and communicate state changes of existing calls. This is the first method that is called
     * after a the framework binds to the call service.
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
     * API is made public.
     *
     * @param callInfo The details of the relevant call.
     */
    void isCompatibleWith(in CallInfo callInfo);

    /**
     * Attempts to call the relevant party using the specified call's handle, be it a phone number,
     * SIP address, or some other kind of user ID.  Note that the set of handle types is
     * dynamically extensible since call providers should be able to implement arbitrary
     * handle-calling systems.  See {@link #isCompatibleWith}. It is expected that the
     * call service respond via {@link ICallServiceAdapter#newOutgoingCall} if it can successfully
     * make the call.
     * TODO(santoscordon): Figure out how a calls service can short-circuit a failure to
     * the adapter.
     *
     * @param callInfo The details of the relevant call.
     */
    void call(in CallInfo callInfo);

    /**
     * Disconnects the call identified by callId.
     *
     * @param callId The identifier of the call to disconnect.
     */
    void disconnect(String callId);
}
