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

import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;

/**
 * A SIP session that is associated with a SIP dialog or a transaction that is
 * not within a dialog.
 * @hide
 */
interface ISipSession {
    /**
     * Gets the IP address of the local host on which this SIP session runs.
     *
     * @return the IP address of the local host
     */
    String getLocalIp();

    /**
     * Gets the SIP profile that this session is associated with.
     *
     * @return the SIP profile that this session is associated with
     */
    SipProfile getLocalProfile();

    /**
     * Gets the SIP profile that this session is connected to. Only available
     * when the session is associated with a SIP dialog.
     *
     * @return the SIP profile that this session is connected to
     */
    SipProfile getPeerProfile();

    /**
     * Gets the session state. The value returned must be one of the states in
     * {@link SipSessionState}.
     *
     * @return the session state
     */
    int getState();

    /**
     * Checks if the session is in a call.
     *
     * @return true if the session is in a call
     */
    boolean isInCall();

    /**
     * Gets the call ID of the session.
     *
     * @return the call ID
     */
    String getCallId();


    /**
     * Sets the listener to listen to the session events. A {@link ISipSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    void setListener(in ISipSessionListener listener);


    /**
     * Performs registration to the server specified by the associated local
     * profile. The session listener is called back upon success or failure of
     * registration. The method is only valid to call when the session state is
     * in {@link SipSessionState#READY_TO_CALL}.
     *
     * @param duration duration in second before the registration expires
     * @see ISipSessionListener
     */
    void register(int duration);

    /**
     * Performs unregistration to the server specified by the associated local
     * profile. Unregistration is technically the same as registration with zero
     * expiration duration. The session listener is called back upon success or
     * failure of unregistration. The method is only valid to call when the
     * session state is in {@link SipSessionState#READY_TO_CALL}.
     *
     * @see ISipSessionListener
     */
    void unregister();

    /**
     * Initiates a call to the specified profile. The session listener is called
     * back upon defined session events. The method is only valid to call when
     * the session state is in {@link SipSessionState#READY_TO_CALL}.
     *
     * @param callee the SIP profile to make the call to
     * @param sessionDescription the session description of this call
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds
     * @see ISipSessionListener
     */
    void makeCall(in SipProfile callee, String sessionDescription, int timeout);

    /**
     * Answers an incoming call with the specified session description. The
     * method is only valid to call when the session state is in
     * {@link SipSessionState#INCOMING_CALL}.
     *
     * @param sessionDescription the session description to answer this call
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds
     */
    void answerCall(String sessionDescription, int timeout);

    /**
     * Ends an established call, terminates an outgoing call or rejects an
     * incoming call. The method is only valid to call when the session state is
     * in {@link SipSessionState#IN_CALL},
     * {@link SipSessionState#INCOMING_CALL},
     * {@link SipSessionState#OUTGOING_CALL} or
     * {@link SipSessionState#OUTGOING_CALL_RING_BACK}.
     */
    void endCall();

    /**
     * Changes the session description during a call. The method is only valid
     * to call when the session state is in {@link SipSessionState#IN_CALL}.
     *
     * @param sessionDescription the new session description
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds
     */
    void changeCall(String sessionDescription, int timeout);
}
