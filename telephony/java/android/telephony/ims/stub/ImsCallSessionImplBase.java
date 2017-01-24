/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims.stub;

import android.os.Message;
import android.os.RemoteException;

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.internal.ImsCallSession;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsVideoCallProvider;

/**
 * Base implementation of IImsCallSession, which implements stub versions of the methods in the
 * IImsCallSession AIDL. Override the methods that your implementation of ImsCallSession supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsCallSession maintained by other ImsServices.
 *
 * @hide
 */

public class ImsCallSessionImplBase extends IImsCallSession.Stub {

    /**
     * Closes the object. This object is not usable after being closed.
     */
    @Override
    public void close() throws RemoteException {

    }

    /**
     * Gets the call ID of the session.
     *
     * @return the call ID
     */
    @Override
    public String getCallId() throws RemoteException {
        return null;
    }

    /**
     * Gets the call profile that this session is associated with
     *
     * @return the {@link ImsCallProfile} that this session is associated with
     */
    @Override
    public ImsCallProfile getCallProfile() throws RemoteException {
        return null;
    }

    /**
     * Gets the local call profile that this session is associated with
     *
     * @return the local {@link ImsCallProfile} that this session is associated with
     */
    @Override
    public ImsCallProfile getLocalCallProfile() throws RemoteException {
        return null;
    }

    /**
     * Gets the remote call profile that this session is associated with
     *
     * @return the remote {@link ImsCallProfile} that this session is associated with
     */
    @Override
    public ImsCallProfile getRemoteCallProfile() throws RemoteException {
        return null;
    }

    /**
     * Gets the value associated with the specified property of this session.
     *
     * @return the string value associated with the specified property
     */
    @Override
    public String getProperty(String name) throws RemoteException {
        return null;
    }

    /**
     * Gets the session state.
     * The value returned must be one of the states in {@link ImsCallSession.State}.
     *
     * @return the session state
     */
    @Override
    public int getState() throws RemoteException {
        return ImsCallSession.State.INVALID;
    }

    /**
     * Checks if the session is in call.
     *
     * @return true if the session is in call, false otherwise
     */
    @Override
    public boolean isInCall() throws RemoteException {
        return false;
    }

    /**
     * Sets the listener to listen to the session events. An {@link ImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    @Override
    public void setListener(IImsCallSessionListener listener) throws RemoteException {
    }

    /**
     * Mutes or unmutes the mic for the active call.
     *
     * @param muted true if the call is muted, false otherwise
     */
    @Override
    public void setMute(boolean muted) throws RemoteException {
    }

    /**
     * Initiates an IMS call with the specified target and call profile.
     * The session listener set in {@link #setListener} is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession.State#IDLE}.
     *
     * @param callee dialed string to make the call to
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see {@link ImsCallSession.Listener#callSessionStarted},
     * {@link ImsCallSession.Listener#callSessionStartFailed}
     */
    @Override
    public void start(String callee, ImsCallProfile profile) throws RemoteException {
    }

    /**
     * Initiates an IMS call with the specified participants and call profile.
     * The session listener set in {@link #setListener} is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession.State#IDLE}.
     *
     * @param participants participant list to initiate an IMS conference call
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see {@link ImsCallSession.Listener#callSessionStarted},
     * {@link ImsCallSession.Listener#callSessionStartFailed}
     */
    @Override
    public void startConference(String[] participants, ImsCallProfile profile)
            throws RemoteException {
    }

    /**
     * Accepts an incoming call or session update.
     *
     * @param callType call type specified in {@link ImsCallProfile} to be answered
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be answered
     * @see {@link ImsCallSession.Listener#callSessionStarted}
     */
    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) throws RemoteException {
    }

    /**
     * Rejects an incoming call or session update.
     *
     * @param reason reason code to reject an incoming call, defined in
     *         com.android.ims.ImsReasonInfo
     * {@link ImsCallSession.Listener#callSessionStartFailed}
     */
    @Override
    public void reject(int reason) throws RemoteException {
    }

    /**
     * Terminates a call.
     *
     * @param reason reason code to terminate a call, defined in
     *         com.android.ims.ImsReasonInfo
     *
     * @see {@link ImsCallSession.Listener#callSessionTerminated}
     */
    @Override
    public void terminate(int reason) throws RemoteException {
    }

    /**
     * Puts a call on hold. When it succeeds, {@link ImsCallSession.Listener#callSessionHeld} is
     * called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to hold the call
     * @see {@link ImsCallSession.Listener#callSessionHeld},
     * {@link ImsCallSession.Listener#callSessionHoldFailed}
     */
    @Override
    public void hold(ImsStreamMediaProfile profile) throws RemoteException {
    }

    /**
     * Continues a call that's on hold. When it succeeds,
     * {@link ImsCallSession.Listener#callSessionResumed} is called.
     *
     * @param profile stream media profile with {@link ImsStreamMediaProfile} to resume the call
     * @see {@link ImsCallSession.Listener#callSessionResumed},
     * {@link ImsCallSession.Listener#callSessionResumeFailed}
     */
    @Override
    public void resume(ImsStreamMediaProfile profile) throws RemoteException {
    }

    /**
     * Merges the active & hold call. When the merge starts,
     * {@link ImsCallSession.Listener#callSessionMergeStarted} is called.
     * {@link ImsCallSession.Listener#callSessionMergeComplete} is called if the merge is
     * successful, and {@link ImsCallSession.Listener#callSessionMergeFailed} is called if the merge
     * fails.
     *
     * @see {@link ImsCallSession.Listener#callSessionMergeStarted},
     * {@link ImsCallSession.Listener#callSessionMergeComplete},
     *      {@link ImsCallSession.Listener#callSessionMergeFailed}
     */
    @Override
    public void merge() throws RemoteException {
    }

    /**
     * Updates the current call's properties (ex. call mode change: video upgrade / downgrade).
     *
     * @param callType call type specified in {@link ImsCallProfile} to be updated
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be updated
     * @see {@link ImsCallSession.Listener#callSessionUpdated},
     * {@link ImsCallSession.Listener#callSessionUpdateFailed}
     */
    @Override
    public void update(int callType, ImsStreamMediaProfile profile) throws RemoteException {
    }

    /**
     * Extends this call to the conference call with the specified recipients.
     *
     * @param participants participant list to be invited to the conference call after extending the
     * call
     * @see {@link ImsCallSession.Listener#callSessionConferenceExtended},
     * {@link ImsCallSession.Listener#callSessionConferenceExtendFailed}
     */
    @Override
    public void extendToConference(String[] participants) throws RemoteException {
    }

    /**
     * Requests the conference server to invite an additional participants to the conference.
     *
     * @param participants participant list to be invited to the conference call
     * @see {@link ImsCallSession.Listener#callSessionInviteParticipantsRequestDelivered},
     *      {@link ImsCallSession.Listener#callSessionInviteParticipantsRequestFailed}
     */
    @Override
    public void inviteParticipants(String[] participants) throws RemoteException {
    }

    /**
     * Requests the conference server to remove the specified participants from the conference.
     *
     * @param participants participant list to be removed from the conference call
     * @see {@link ImsCallSession.Listener#callSessionRemoveParticipantsRequestDelivered},
     *      {@link ImsCallSession.Listener#callSessionRemoveParticipantsRequestFailed}
     */
    @Override
    public void removeParticipants(String[] participants) throws RemoteException {
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void sendDtmf(char c, Message result) throws RemoteException {
    }

    /**
     * Start a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void startDtmf(char c) throws RemoteException {
    }

    /**
     * Stop a DTMF code.
     */
    @Override
    public void stopDtmf() throws RemoteException {
    }

    /**
     * Sends an USSD message.
     *
     * @param ussdMessage USSD message to send
     */
    @Override
    public void sendUssd(String ussdMessage) throws RemoteException {
    }

    /**
     * Returns a binder for the video call provider implementation contained within the IMS service
     * process. This binder is used by the VideoCallProvider subclass in Telephony which
     * intermediates between the propriety implementation and Telecomm/InCall.
     */
    @Override
    public IImsVideoCallProvider getVideoCallProvider() throws RemoteException {
        return null;
    }

    /**
     * Determines if the current session is multiparty.
     * @return {@code True} if the session is multiparty.
     */
    @Override
    public boolean isMultiparty() throws RemoteException {
        return false;
    }

    /**
     * Device issues RTT modify request
     * @param toProfile The profile with requested changes made
     */
    @Override
    public void sendRttModifyRequest(ImsCallProfile toProfile) {
    }

    /**
     * Device responds to Remote RTT modify request
     * @param status true  Accepted the request
     *                false  Declined the request
     */
    @Override
    public void sendRttModifyResponse(boolean status) {
    }

    /**
     * Device sends RTT message
     * @param rttMessage RTT message to be sent
     */
    @Override
    public void sendRttMessage(String rttMessage) {
    }
}
