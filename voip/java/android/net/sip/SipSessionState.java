/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.sip;

/**
 * Defines {@link ISipSession} states.
 * @hide
 */
public enum SipSessionState {
    /** When session is ready to initiate a call or transaction. */
    READY_TO_CALL,

    /** When the registration request is sent out. */
    REGISTERING,

    /** When the unregistration request is sent out. */
    DEREGISTERING,

    /** When an INVITE request is received. */
    INCOMING_CALL,

    /** When an OK response is sent for the INVITE request received. */
    INCOMING_CALL_ANSWERING,

    /** When an INVITE request is sent. */
    OUTGOING_CALL,

    /** When a RINGING response is received for the INVITE request sent. */
    OUTGOING_CALL_RING_BACK,

    /** When a CANCEL request is sent for the INVITE request sent. */
    OUTGOING_CALL_CANCELING,

    /** When a call is established. */
    IN_CALL,

    /** Some error occurs when making a remote call to {@link ISipSession}. */
    REMOTE_ERROR,

    /** When an OPTIONS request is sent. */
    PINGING;

    /**
     * Checks if the specified string represents the same state as this object.
     *
     * @return true if the specified string represents the same state as this
     *      object
     */
    public boolean equals(String state) {
        return toString().equals(state);
    }
}
