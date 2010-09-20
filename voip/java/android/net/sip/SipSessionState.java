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
public class SipSessionState {
    /** When session is ready to initiate a call or transaction. */
    public static final int READY_TO_CALL = 0;

    /** When the registration request is sent out. */
    public static final int REGISTERING = 1;

    /** When the unregistration request is sent out. */
    public static final int DEREGISTERING = 2;

    /** When an INVITE request is received. */
    public static final int INCOMING_CALL = 3;

    /** When an OK response is sent for the INVITE request received. */
    public static final int INCOMING_CALL_ANSWERING = 4;

    /** When an INVITE request is sent. */
    public static final int OUTGOING_CALL = 5;

    /** When a RINGING response is received for the INVITE request sent. */
    public static final int OUTGOING_CALL_RING_BACK = 6;

    /** When a CANCEL request is sent for the INVITE request sent. */
    public static final int OUTGOING_CALL_CANCELING = 7;

    /** When a call is established. */
    public static final int IN_CALL = 8;

    /** Some error occurs when making a remote call to {@link ISipSession}. */
    public static final int REMOTE_ERROR = 9;

    /** When an OPTIONS request is sent. */
    public static final int PINGING = 10;

    /** Not defined. */
    public static final int NOT_DEFINED = 101;

    /**
     * Converts the state to string.
     */
    public static String toString(int state) {
        switch (state) {
            case READY_TO_CALL:
                return "READY_TO_CALL";
            case REGISTERING:
                return "REGISTERING";
            case DEREGISTERING:
                return "DEREGISTERING";
            case INCOMING_CALL:
                return "INCOMING_CALL";
            case INCOMING_CALL_ANSWERING:
                return "INCOMING_CALL_ANSWERING";
            case OUTGOING_CALL:
                return "OUTGOING_CALL";
            case OUTGOING_CALL_RING_BACK:
                return "OUTGOING_CALL_RING_BACK";
            case OUTGOING_CALL_CANCELING:
                return "OUTGOING_CALL_CANCELING";
            case IN_CALL:
                return "IN_CALL";
            case REMOTE_ERROR:
                return "REMOTE_ERROR";
            case PINGING:
                return "PINGING";
            default:
                return "NOT_DEFINED";
        }
    }

    private SipSessionState() {
    }
}
