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

import android.net.sip.ISipSession;
import android.net.sip.SipProfile;

/**
 * Listener class to listen to SIP session events.
 * @hide
 */
interface ISipSessionListener {
    /**
     * Called when an INVITE request is sent to initiate a new call.
     *
     * @param session the session object that carries out the transaction
     */
    void onCalling(in ISipSession session);

    /**
     * Called when an INVITE request is received.
     *
     * @param session the session object that carries out the transaction
     * @param caller the SIP profile of the caller
     * @param sessionDescription the caller's session description
     */
    void onRinging(in ISipSession session, in SipProfile caller,
            String sessionDescription);

    /**
     * Called when a RINGING response is received for the INVITE request sent
     *
     * @param session the session object that carries out the transaction
     */
    void onRingingBack(in ISipSession session);

    /**
     * Called when the session is established.
     *
     * @param session the session object that is associated with the dialog
     * @param sessionDescription the peer's session description
     */
    void onCallEstablished(in ISipSession session,
            String sessionDescription);

    /**
     * Called when the session is terminated.
     *
     * @param session the session object that is associated with the dialog
     */
    void onCallEnded(in ISipSession session);

    /**
     * Called when the peer is busy during session initialization.
     *
     * @param session the session object that carries out the transaction
     */
    void onCallBusy(in ISipSession session);

    /**
     * Called when the call is being transferred to a new one.
     *
     * @param newSession the new session that the call will be transferred to
     * @param sessionDescription the new peer's session description
     */
    void onCallTransferring(in ISipSession newSession, String sessionDescription);

    /**
     * Called when an error occurs during session initialization and
     * termination.
     *
     * @param session the session object that carries out the transaction
     * @param errorCode error code defined in {@link SipErrorCode}
     * @param errorMessage error message
     */
    void onError(in ISipSession session, int errorCode, String errorMessage);

    /**
     * Called when an error occurs during session modification negotiation.
     *
     * @param session the session object that carries out the transaction
     * @param errorCode error code defined in {@link SipErrorCode}
     * @param errorMessage error message
     */
    void onCallChangeFailed(in ISipSession session, int errorCode,
            String errorMessage);

    /**
     * Called when a registration request is sent.
     *
     * @param session the session object that carries out the transaction
     */
    void onRegistering(in ISipSession session);

    /**
     * Called when registration is successfully done.
     *
     * @param session the session object that carries out the transaction
     * @param duration duration in second before the registration expires
     */
    void onRegistrationDone(in ISipSession session, int duration);

    /**
     * Called when the registration fails.
     *
     * @param session the session object that carries out the transaction
     * @param errorCode error code defined in {@link SipErrorCode}
     * @param errorMessage error message
     */
    void onRegistrationFailed(in ISipSession session, int errorCode,
            String errorMessage);

    /**
     * Called when the registration gets timed out.
     *
     * @param session the session object that carries out the transaction
     */
    void onRegistrationTimeout(in ISipSession session);
}
