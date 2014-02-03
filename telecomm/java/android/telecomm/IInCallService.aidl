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

package android.telecomm;

import android.telecomm.CallInfo;
import android.telecomm.IInCallAdapter;

/**
 * This service is implemented by any app that wishes to provide the user-interface for managing
 * phone calls. Telecomm binds to this service while there exists a live (active or incoming)
 * call, and uses it to notify the in-call app of any live and and recently disconnected calls.
 * TODO(santoscordon): Needs more/better description of lifecycle once the interface is better
 * defined.
 * TODO(santoscordon): What happens if two or more apps on a given decide implement this interface?
 */
oneway interface IInCallService {

    /**
     * Provides the in-call app an adapter object through which to send call-commands such as
     * answering and rejecting incoming calls, disconnecting active calls, and putting calls in
     * special states (mute, hold, etc).
     *
     * @param inCallAdapter Adapter through which an in-call app can send call-commands to Telecomm.
     */
    void setInCallAdapter(in IInCallAdapter inCallAdapter);

    /**
     * Indicates to the in-call app that a new call has been created and an appropriate
     * user-interface should be built and shown to notify the user. Information about the call
     * including its current state is passed in through the callInfo object.
     *
     * @param callInfo Information about the new call.
     */
    void addCall(in CallInfo callInfo);

    /**
     * Indicates to the in-call app that a call has moved to the active state.
     * TODO(santoscordon): link javadoc to "active" constant once CallState is defined.
     *
     * @param callId The identifier of the call that became active.
     */
    void setActive(String callId);

    /**
     * Indicates to the in-call app that a call has been disconnected and the user should be
     * notified.
     * TODO(santoscordon): link javadoc to "disconnected" constant once CallState is defined.
     * TODO(santoscordon): Needs disconnect-cause either as a numberical constant, string or both
     * depending on what is ultimately needed to support all scenarios.
     *
     * @param callId The identifier of the call that was disconnected.
     */
    void setDisconnected(String callId);
}
