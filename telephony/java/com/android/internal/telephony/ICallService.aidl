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

import com.android.internal.telephony.ICallServiceAdapter;

/**
 * Service interface for services which would like to provide calls to be
 * managed by the system in-call UI.
 *
 * This interface provides methods that the android framework can use to deliver commands
 * for calls provided by this call service including making new calls and disconnecting
 * existing ones. A binding to ICallService implementations exists for two conditions:
 * 1) There exists one or more live calls for that call service,
 * 2) Prior to an outbound call to test if this call service is compatible with the outgoing call.
 */
oneway interface ICallService {

    /**
     * Determines if the CallService can make calls to the handle.
     * TODO(santoscordon): Move this method into its own service interface long term.
     * TODO(santoscordon): Add response callback parameter.
     */
    void isCompatibleWith(String handle);

    /**
     * Attempts to call the relevant party using the specified handle, be it a phone number,
     * SIP address, or some other kind of user ID.  Note that the set of handle types is
     * dynamically extensible since call providers should be able to implement arbitrary
     * handle-calling systems.  See {@link #isCompatibleWith}.
     * TODO(santoscordon): Should this have a response attached to it to ensure that the call
     * service actually plans to make the call?
     */
    void call(String handle);

    /**
     * Disconnects the call identified by callId.
     */
    void disconnect(String callId);

    /**
     * Sets an implementation of ICallServiceAdapter which the call service can use to add new calls
     * and communicate state changes of existing calls. This is the first method that is called
     * after a the framework binds to the call service.
     */
    void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter);
}
