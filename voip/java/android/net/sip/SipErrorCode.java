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
 * Defines error codes returned during SIP actions. For example, during
 * {@link SipRegistrationListener#onRegistrationFailed onRegistrationFailed()},
 * {@link SipSession.Listener#onError onError()},
 * {@link SipSession.Listener#onCallChangeFailed onCallChangeFailed()} and
 * {@link SipSession.Listener#onRegistrationFailed onRegistrationFailed()}.
 */
public class SipErrorCode {
    /** Not an error. */
    public static final int NO_ERROR = 0;

    /** When some socket error occurs. */
    public static final int SOCKET_ERROR = -1;

    /** When server responds with an error. */
    public static final int SERVER_ERROR = -2;

    /** When transaction is terminated unexpectedly. */
    public static final int TRANSACTION_TERMINTED = -3;

    /** When some error occurs on the device, possibly due to a bug. */
    public static final int CLIENT_ERROR = -4;

    /** When the transaction gets timed out. */
    public static final int TIME_OUT = -5;

    /** When the remote URI is not valid. */
    public static final int INVALID_REMOTE_URI = -6;

    /** When the peer is not reachable. */
    public static final int PEER_NOT_REACHABLE = -7;

    /** When invalid credentials are provided. */
    public static final int INVALID_CREDENTIALS = -8;

    /** The client is in a transaction and cannot initiate a new one. */
    public static final int IN_PROGRESS = -9;

    /** When data connection is lost. */
    public static final int DATA_CONNECTION_LOST = -10;

    /** Cross-domain authentication required. */
    public static final int CROSS_DOMAIN_AUTHENTICATION = -11;

    /** When the server is not reachable. */
    public static final int SERVER_UNREACHABLE = -12;

    public static String toString(int errorCode) {
        switch (errorCode) {
            case NO_ERROR:
                return "NO_ERROR";
            case SOCKET_ERROR:
                return "SOCKET_ERROR";
            case SERVER_ERROR:
                return "SERVER_ERROR";
            case TRANSACTION_TERMINTED:
                return "TRANSACTION_TERMINTED";
            case CLIENT_ERROR:
                return "CLIENT_ERROR";
            case TIME_OUT:
                return "TIME_OUT";
            case INVALID_REMOTE_URI:
                return "INVALID_REMOTE_URI";
            case PEER_NOT_REACHABLE:
                return "PEER_NOT_REACHABLE";
            case INVALID_CREDENTIALS:
                return "INVALID_CREDENTIALS";
            case IN_PROGRESS:
                return "IN_PROGRESS";
            case DATA_CONNECTION_LOST:
                return "DATA_CONNECTION_LOST";
            case CROSS_DOMAIN_AUTHENTICATION:
                return "CROSS_DOMAIN_AUTHENTICATION";
            case SERVER_UNREACHABLE:
                return "SERVER_UNREACHABLE";
            default:
                return "UNKNOWN";
        }
    }

    private SipErrorCode() {
    }
}
