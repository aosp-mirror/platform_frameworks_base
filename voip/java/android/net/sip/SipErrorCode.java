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
 * Defines error code returned in
 * {@link SipRegistrationListener#onRegistrationFailed},
 * {@link ISipSessionListener#onError},
 * {@link ISipSessionListener#onCallChangeFailed} and
 * {@link ISipSessionListener#onRegistrationFailed}.
 * @hide
 */
public enum SipErrorCode {
    /** When some socket error occurs. */
    SOCKET_ERROR,

    /** When server responds with an error. */
    SERVER_ERROR,

    /** When transaction is terminated unexpectedly. */
    TRANSACTION_TERMINTED,

    /** When some error occurs on the device, possibly due to a bug. */
    CLIENT_ERROR,

    /** When the transaction gets timed out. */
    TIME_OUT,

    /** When the remote URI is not valid. */
    INVALID_REMOTE_URI,

    /** When the peer is not reachable. */
    PEER_NOT_REACHABLE,

    /** When invalid credentials are provided. */
    INVALID_CREDENTIALS,

    /** The client is in a transaction and cannot initiate a new one. */
    IN_PROGRESS,

    /** When data connection is lost. */
    DATA_CONNECTION_LOST;
}
