/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.os.Message;
import android.os.RemoteException;
import android.telephony.CallQuality;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsVideoCallProvider;

import java.util.Objects;

/**
 * Provides the call initiation/termination, and media exchange between two IMS endpoints.
 * It directly communicates with IMS service which implements the IMS protocol behavior.
 *
 * @hide
 */
public class ImsCallSession {
    private static final String TAG = "ImsCallSession";

    /**
     * Defines IMS call session state. Please use
     * {@link android.telephony.ims.stub.ImsCallSessionImplBase.State} definition.
     * This is kept around for capability reasons.
     */
    public static class State {
        public static final int IDLE = 0;
        public static final int INITIATED = 1;
        public static final int NEGOTIATING = 2;
        public static final int ESTABLISHING = 3;
        public static final int ESTABLISHED = 4;

        public static final int RENEGOTIATING = 5;
        public static final int REESTABLISHING = 6;

        public static final int TERMINATING = 7;
        public static final int TERMINATED = 8;

        public static final int INVALID = (-1);

        /**
         * Converts the state to string.
         */
        public static String toString(int state) {
            switch (state) {
                case IDLE:
                    return "IDLE";
                case INITIATED:
                    return "INITIATED";
                case NEGOTIATING:
                    return "NEGOTIATING";
                case ESTABLISHING:
                    return "ESTABLISHING";
                case ESTABLISHED:
                    return "ESTABLISHED";
                case RENEGOTIATING:
                    return "RENEGOTIATING";
                case REESTABLISHING:
                    return "REESTABLISHING";
                case TERMINATING:
                    return "TERMINATING";
                case TERMINATED:
                    return "TERMINATED";
                default:
                    return "UNKNOWN";
            }
        }

        private State() {
        }
    }

    /**
     * Listener for events relating to an IMS session, such as when a session is being
     * recieved ("on ringing") or a call is outgoing ("on calling").
     * <p>Many of these events are also received by {@link ImsCall.Listener}.</p>
     * @hide
     */
    public static class Listener {
        /**
         * Called when a request is sent out to initiate a new session
         * and 1xx response is received from the network.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionProgressing(ImsCallSession session,
                ImsStreamMediaProfile profile) {
            // no-op
        }

        /**
         * Called when the session is established.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionStarted(ImsCallSession session,
                ImsCallProfile profile) {
            // no-op
        }

        /**
         * Called when the session establishment is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the session establishment failure
         */
        public void callSessionStartFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session is terminated.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the session termination
         */
        public void callSessionTerminated(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session is in hold.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionHeld(ImsCallSession session,
                ImsCallProfile profile) {
        }

        /**
         * Called when the session hold is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the session hold failure
         */
        public void callSessionHoldFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session hold is received from the remote user.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionHoldReceived(ImsCallSession session,
                ImsCallProfile profile) {
        }

        /**
         * Called when the session resume is done.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionResumed(ImsCallSession session,
                ImsCallProfile profile) {
        }

        /**
         * Called when the session resume is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the session resume failure
         */
        public void callSessionResumeFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session resume is received from the remote user.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionResumeReceived(ImsCallSession session,
                ImsCallProfile profile) {
        }

        /**
         * Called when the session merge has been started.  At this point, the {@code newSession}
         * represents the session which has been initiated to the IMS conference server for the
         * new merged conference.
         *
         * @param session the session object that carries out the IMS session
         * @param newSession the session object that is merged with an active & hold session
         */
        public void callSessionMergeStarted(ImsCallSession session,
                ImsCallSession newSession, ImsCallProfile profile) {
        }

        /**
         * Called when the session merge is successful and the merged session is active.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionMergeComplete(ImsCallSession session) {
        }

        /**
         * Called when the session merge has failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the call merge failure
         */
        public void callSessionMergeFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session is updated (except for hold/unhold).
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionUpdated(ImsCallSession session,
                ImsCallProfile profile) {
        }

        /**
         * Called when the session update is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the session update failure
         */
        public void callSessionUpdateFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the session update is received from the remote user.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionUpdateReceived(ImsCallSession session,
                ImsCallProfile profile) {
            // no-op
        }

        /**
         * Called when the session is extended to the conference session.
         *
         * @param session the session object that carries out the IMS session
         * @param newSession the session object that is extended to the conference
         *      from the active session
         */
        public void callSessionConferenceExtended(ImsCallSession session,
                ImsCallSession newSession, ImsCallProfile profile) {
        }

        /**
         * Called when the conference extension is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the conference extension failure
         */
        public void callSessionConferenceExtendFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
        }

        /**
         * Called when the conference extension is received from the remote user.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionConferenceExtendReceived(ImsCallSession session,
                ImsCallSession newSession, ImsCallProfile profile) {
            // no-op
        }

        /**
         * Called when the invitation request of the participants is delivered to the conference
         * server.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionInviteParticipantsRequestDelivered(ImsCallSession session) {
            // no-op
        }

        /**
         * Called when the invitation request of the participants is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the conference invitation failure
         */
        public void callSessionInviteParticipantsRequestFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
            // no-op
        }

        /**
         * Called when the removal request of the participants is delivered to the conference
         * server.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionRemoveParticipantsRequestDelivered(ImsCallSession session) {
            // no-op
        }

        /**
         * Called when the removal request of the participants is failed.
         *
         * @param session the session object that carries out the IMS session
         * @param reasonInfo detailed reason of the conference removal failure
         */
        public void callSessionRemoveParticipantsRequestFailed(ImsCallSession session,
                ImsReasonInfo reasonInfo) {
            // no-op
        }

        /**
         * Called when the conference state is updated.
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionConferenceStateUpdated(ImsCallSession session,
                ImsConferenceState state) {
            // no-op
        }

        /**
         * Called when the USSD message is received from the network.
         *
         * @param mode mode of the USSD message (REQUEST / NOTIFY)
         * @param ussdMessage USSD message
         */
        public void callSessionUssdMessageReceived(ImsCallSession session,
                int mode, String ussdMessage) {
            // no-op
        }

        /**
         * Called when an {@link ImsCallSession} may handover from one radio technology to another.
         * For example, the session may handover from WIFI to LTE if conditions are right.
         * <p>
         * If handover is attempted,
         * {@link #callSessionHandover(ImsCallSession, int, int, ImsReasonInfo)} or
         * {@link #callSessionHandoverFailed(ImsCallSession, int, int, ImsReasonInfo)} will be
         * called to indicate the success or failure of the handover.
         *
         * @param session IMS session object
         * @param srcAccessTech original access technology
         * @param targetAccessTech new access technology
         */
        public void callSessionMayHandover(ImsCallSession session, int srcAccessTech,
                int targetAccessTech) {
            // no-op
        }

        /**
         * Called when session access technology changes
         *
         * @param session IMS session object
         * @param srcAccessTech original access technology
         * @param targetAccessTech new access technology
         * @param reasonInfo
         */
        public void callSessionHandover(ImsCallSession session,
                                 int srcAccessTech, int targetAccessTech,
                                 ImsReasonInfo reasonInfo) {
            // no-op
        }

        /**
         * Called when session access technology change fails
         *
         * @param session IMS session object
         * @param srcAccessTech original access technology
         * @param targetAccessTech new access technology
         * @param reasonInfo handover failure reason
         */
        public void callSessionHandoverFailed(ImsCallSession session,
                                       int srcAccessTech, int targetAccessTech,
                                       ImsReasonInfo reasonInfo) {
            // no-op
        }

        /**
         * Called when TTY mode of remote party changed
         *
         * @param session IMS session object
         * @param mode TTY mode of remote party
         */
        public void callSessionTtyModeReceived(ImsCallSession session,
                                       int mode) {
            // no-op
        }

        /**
         * Notifies of a change to the multiparty state for this {@code ImsCallSession}.
         *
         * @param session The call session.
         * @param isMultiParty {@code true} if the session became multiparty, {@code false}
         *      otherwise.
         */
        public void callSessionMultipartyStateChanged(ImsCallSession session,
                boolean isMultiParty) {
            // no-op
        }

        /**
         * Called when the session supplementary service is received
         *
         * @param session the session object that carries out the IMS session
         */
        public void callSessionSuppServiceReceived(ImsCallSession session,
                ImsSuppServiceNotification suppServiceInfo) {
        }

        /**
         * Received RTT modify request from Remote Party
         */
        public void callSessionRttModifyRequestReceived(ImsCallSession session,
            ImsCallProfile callProfile) {
            // no-op
        }

        /**
         * Received response for RTT modify request
         */
        public void callSessionRttModifyResponseReceived(int status) {
            // no -op
        }

        /**
         * Device received RTT message from Remote UE
         */
        public void callSessionRttMessageReceived(String rttMessage) {
            // no-op
        }

        /**
         * While in call, there has been a change in RTT audio indicator.
         */
        public void callSessionRttAudioIndicatorChanged(ImsStreamMediaProfile profile) {
            // no-op
        }

        /**
         * Called when the IMS service reports a change to the call quality.
         */
        public void callQualityChanged(CallQuality callQuality) {
            // no-op
        }
    }

    private final IImsCallSession miSession;
    private boolean mClosed = false;
    private Listener mListener;

    /** @hide */
    public ImsCallSession(IImsCallSession iSession) {
        miSession = iSession;

        if (iSession != null) {
            try {
                iSession.setListener(new IImsCallSessionListenerProxy());
            } catch (RemoteException e) {
            }
        } else {
            mClosed = true;
        }
    }

    /** @hide */
    public ImsCallSession(IImsCallSession iSession, Listener listener) {
        this(iSession);
        setListener(listener);
    }

    /**
     * Closes this object. This object is not usable after being closed.
     */
    public void close() {
        synchronized (this) {
            if (mClosed) {
                return;
            }

            try {
                miSession.close();
                mClosed = true;
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Gets the call ID of the session.
     *
     * @return the call ID
     */
    public String getCallId() {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getCallId();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the call profile that this session is associated with
     *
     * @return the call profile that this session is associated with
     */
    public ImsCallProfile getCallProfile() {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getCallProfile();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the local call profile that this session is associated with
     *
     * @return the local call profile that this session is associated with
     */
    public ImsCallProfile getLocalCallProfile() {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getLocalCallProfile();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the remote call profile that this session is associated with
     *
     * @return the remote call profile that this session is associated with
     */
    public ImsCallProfile getRemoteCallProfile() {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getRemoteCallProfile();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the video call provider for the session.
     *
     * @return The video call provider.
     * @hide
     */
    public IImsVideoCallProvider getVideoCallProvider() {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getVideoCallProvider();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the value associated with the specified property of this session.
     *
     * @return the string value associated with the specified property
     */
    public String getProperty(String name) {
        if (mClosed) {
            return null;
        }

        try {
            return miSession.getProperty(name);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Gets the session state.
     * The value returned must be one of the states in {@link State}.
     *
     * @return the session state
     */
    public int getState() {
        if (mClosed) {
            return State.INVALID;
        }

        try {
            return miSession.getState();
        } catch (RemoteException e) {
            return State.INVALID;
        }
    }

    /**
     * Determines if the {@link ImsCallSession} is currently alive (e.g. not in a terminated or
     * closed state).
     *
     * @return {@code True} if the session is alive.
     */
    public boolean isAlive() {
        if (mClosed) {
            return false;
        }

        int state = getState();
        switch (state) {
            case State.IDLE:
            case State.INITIATED:
            case State.NEGOTIATING:
            case State.ESTABLISHING:
            case State.ESTABLISHED:
            case State.RENEGOTIATING:
            case State.REESTABLISHING:
                return true;
            default:
                return false;
        }
    }

    /**
     * Gets the native IMS call session.
     * @hide
     */
    public IImsCallSession getSession() {
        return miSession;
    }

    /**
     * Checks if the session is in call.
     *
     * @return true if the session is in call
     */
    public boolean isInCall() {
        if (mClosed) {
            return false;
        }

        try {
            return miSession.isInCall();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the listener to listen to the session events. A {@link ImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     * @hide
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Mutes or unmutes the mic for the active call.
     *
     * @param muted true if the call is muted, false otherwise
     */
    public void setMute(boolean muted) {
        if (mClosed) {
            return;
        }

        try {
            miSession.setMute(muted);
        } catch (RemoteException e) {
        }
    }

    /**
     * Initiates an IMS call with the specified target and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession.State#IDLE}.
     *
     * @param callee dialed string to make the call to
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see Listener#callSessionStarted, Listener#callSessionStartFailed
     */
    public void start(String callee, ImsCallProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.start(callee, profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Initiates an IMS conference call with the specified target and call profile.
     * The session listener is called back upon defined session events.
     * The method is only valid to call when the session state is in
     * {@link ImsCallSession.State#IDLE}.
     *
     * @param participants participant list to initiate an IMS conference call
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see Listener#callSessionStarted, Listener#callSessionStartFailed
     */
    public void start(String[] participants, ImsCallProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.startConference(participants, profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Accepts an incoming call or session update.
     *
     * @param callType call type specified in {@link ImsCallProfile} to be answered
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be answered
     * @see Listener#callSessionStarted
     */
    public void accept(int callType, ImsStreamMediaProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.accept(callType, profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Deflects an incoming call.
     *
     * @param number number to be deflected to
     */
    public void deflect(String number) {
        if (mClosed) {
            return;
        }

        try {
            miSession.deflect(number);
        } catch (RemoteException e) {
        }
    }

    /**
     * Rejects an incoming call or session update.
     *
     * @param reason reason code to reject an incoming call
     * @see Listener#callSessionStartFailed
     */
    public void reject(int reason) {
        if (mClosed) {
            return;
        }

        try {
            miSession.reject(reason);
        } catch (RemoteException e) {
        }
    }

    /**
     * Terminates a call.
     *
     * @see Listener#callSessionTerminated
     */
    public void terminate(int reason) {
        if (mClosed) {
            return;
        }

        try {
            miSession.terminate(reason);
        } catch (RemoteException e) {
        }
    }

    /**
     * Puts a call on hold. When it succeeds, {@link Listener#callSessionHeld} is called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to hold the call
     * @see Listener#callSessionHeld, Listener#callSessionHoldFailed
     */
    public void hold(ImsStreamMediaProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.hold(profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Continues a call that's on hold. When it succeeds,
     * {@link Listener#callSessionResumed} is called.
     *
     * @param profile stream media profile {@link ImsStreamMediaProfile} to resume the call
     * @see Listener#callSessionResumed, Listener#callSessionResumeFailed
     */
    public void resume(ImsStreamMediaProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.resume(profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Merges the active & hold call. When it succeeds,
     * {@link Listener#callSessionMergeStarted} is called.
     *
     * @see Listener#callSessionMergeStarted , Listener#callSessionMergeFailed
     */
    public void merge() {
        if (mClosed) {
            return;
        }

        try {
            miSession.merge();
        } catch (RemoteException e) {
        }
    }

    /**
     * Updates the current call's properties (ex. call mode change: video upgrade / downgrade).
     *
     * @param callType call type specified in {@link ImsCallProfile} to be updated
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be updated
     * @see Listener#callSessionUpdated, Listener#callSessionUpdateFailed
     */
    public void update(int callType, ImsStreamMediaProfile profile) {
        if (mClosed) {
            return;
        }

        try {
            miSession.update(callType, profile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Extends this call to the conference call with the specified recipients.
     *
     * @param participants list to be invited to the conference call after extending the call
     * @see Listener#callSessionConferenceExtended
     * @see Listener#callSessionConferenceExtendFailed
     */
    public void extendToConference(String[] participants) {
        if (mClosed) {
            return;
        }

        try {
            miSession.extendToConference(participants);
        } catch (RemoteException e) {
        }
    }

    /**
     * Requests the conference server to invite an additional participants to the conference.
     *
     * @param participants list to be invited to the conference call
     * @see Listener#callSessionInviteParticipantsRequestDelivered
     * @see Listener#callSessionInviteParticipantsRequestFailed
     */
    public void inviteParticipants(String[] participants) {
        if (mClosed) {
            return;
        }

        try {
            miSession.inviteParticipants(participants);
        } catch (RemoteException e) {
        }
    }

    /**
     * Requests the conference server to remove the specified participants from the conference.
     *
     * @param participants participant list to be removed from the conference call
     * @see Listener#callSessionRemoveParticipantsRequestDelivered
     * @see Listener#callSessionRemoveParticipantsRequestFailed
     */
    public void removeParticipants(String[] participants) {
        if (mClosed) {
            return;
        }

        try {
            miSession.removeParticipants(participants);
        } catch (RemoteException e) {
        }
    }


    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    public void sendDtmf(char c, Message result) {
        if (mClosed) {
            return;
        }

        try {
            miSession.sendDtmf(c, result);
        } catch (RemoteException e) {
        }
    }

    /**
     * Starts a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    public void startDtmf(char c) {
        if (mClosed) {
            return;
        }

        try {
            miSession.startDtmf(c);
        } catch (RemoteException e) {
        }
    }

    /**
     * Stops a DTMF code.
     */
    public void stopDtmf() {
        if (mClosed) {
            return;
        }

        try {
            miSession.stopDtmf();
        } catch (RemoteException e) {
        }
    }

    /**
     * Sends an USSD message.
     *
     * @param ussdMessage USSD message to send
     */
    public void sendUssd(String ussdMessage) {
        if (mClosed) {
            return;
        }

        try {
            miSession.sendUssd(ussdMessage);
        } catch (RemoteException e) {
        }
    }

    /**
     * Determines if the session is multiparty.
     *
     * @return {@code True} if the session is multiparty.
     */
    public boolean isMultiparty() {
        if (mClosed) {
            return false;
        }

        try {
            return miSession.isMultiparty();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sends Rtt Message
     *
     * @param rttMessage rtt text to be sent
     */
    public void sendRttMessage(String rttMessage) {
        if (mClosed) {
            return;
        }

        try {
            miSession.sendRttMessage(rttMessage);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sends RTT Upgrade or downgrade request
     *
     * @param to Profile with the RTT flag set to the desired value
     */
    public void sendRttModifyRequest(ImsCallProfile to) {
        if (mClosed) {
            return;
        }

        try {
            miSession.sendRttModifyRequest(to);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sends RTT Upgrade response
     *
     * @param response : response for upgrade
     */
    public void sendRttModifyResponse(boolean response) {
        if (mClosed) {
            return;
        }

        try {
            miSession.sendRttModifyResponse(response);
        } catch (RemoteException e) {
        }
    }

    /**
     * A listener type for receiving notification on IMS call session events.
     * When an event is generated for an {@link IImsCallSession},
     * the application is notified by having one of the methods called on
     * the {@link IImsCallSessionListener}.
     */
    private class IImsCallSessionListenerProxy extends IImsCallSessionListener.Stub {
        /**
         * Notifies the result of the basic session operation (setup / terminate).
         */
        @Override
        public void callSessionProgressing(ImsStreamMediaProfile profile) {
            if (mListener != null) {
                mListener.callSessionProgressing(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionInitiated(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionStarted(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionInitiatedFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionStartFailed(ImsCallSession.this, reasonInfo);
            }
        }

        @Override
        public void callSessionTerminated(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionTerminated(ImsCallSession.this, reasonInfo);
            }
        }

        /**
         * Notifies the result of the call hold/resume operation.
         */
        @Override
        public void callSessionHeld(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionHeld(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionHoldFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionHoldFailed(ImsCallSession.this, reasonInfo);
            }
        }

        @Override
        public void callSessionHoldReceived(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionHoldReceived(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionResumed(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionResumed(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionResumeFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionResumeFailed(ImsCallSession.this, reasonInfo);
            }
        }

        @Override
        public void callSessionResumeReceived(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionResumeReceived(ImsCallSession.this, profile);
            }
        }

        /**
         * Notifies the start of a call merge operation.
         *
         * @param newSession The merged call session.
         * @param profile The call profile.
         */
        @Override
        public void callSessionMergeStarted(IImsCallSession newSession, ImsCallProfile profile) {
            // This callback can be used for future use to add additional
            // functionality that may be needed between conference start and complete
            Log.d(TAG, "callSessionMergeStarted");
        }

        /**
         * Notifies the successful completion of a call merge operation.
         *
         * @param newSession The call session.
         */
        @Override
        public void callSessionMergeComplete(IImsCallSession newSession) {
            if (mListener != null) {
                if (newSession != null) {
                    // New session created after conference
                    ImsCallSession validActiveSession = new ImsCallSession(newSession);
                    mListener.callSessionMergeComplete(validActiveSession);
               } else {
                   // Session already exists. Hence no need to pass
                   mListener.callSessionMergeComplete(null);
               }
            }
        }

        /**
         * Notifies of a failure to perform a call merge operation.
         *
         * @param reasonInfo The merge failure reason.
         */
        @Override
        public void callSessionMergeFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionMergeFailed(ImsCallSession.this, reasonInfo);
            }
        }

        /**
         * Notifies the result of call upgrade / downgrade or any other call updates.
         */
        @Override
        public void callSessionUpdated(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionUpdated(ImsCallSession.this, profile);
            }
        }

        @Override
        public void callSessionUpdateFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionUpdateFailed(ImsCallSession.this, reasonInfo);
            }
        }

        @Override
        public void callSessionUpdateReceived(ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionUpdateReceived(ImsCallSession.this, profile);
            }
        }

        /**
         * Notifies the result of conference extension.
         */
        @Override
        public void callSessionConferenceExtended(IImsCallSession newSession,
                ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionConferenceExtended(ImsCallSession.this,
                        new ImsCallSession(newSession), profile);
            }
        }

        @Override
        public void callSessionConferenceExtendFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionConferenceExtendFailed(ImsCallSession.this, reasonInfo);
            }
        }

        @Override
        public void callSessionConferenceExtendReceived(IImsCallSession newSession,
                ImsCallProfile profile) {
            if (mListener != null) {
                mListener.callSessionConferenceExtendReceived(ImsCallSession.this,
                        new ImsCallSession(newSession), profile);
            }
        }

        /**
         * Notifies the result of the participant invitation / removal to/from
         * the conference session.
         */
        @Override
        public void callSessionInviteParticipantsRequestDelivered() {
            if (mListener != null) {
                mListener.callSessionInviteParticipantsRequestDelivered(ImsCallSession.this);
            }
        }

        @Override
        public void callSessionInviteParticipantsRequestFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionInviteParticipantsRequestFailed(ImsCallSession.this,
                        reasonInfo);
            }
        }

        @Override
        public void callSessionRemoveParticipantsRequestDelivered() {
            if (mListener != null) {
                mListener.callSessionRemoveParticipantsRequestDelivered(ImsCallSession.this);
            }
        }

        @Override
        public void callSessionRemoveParticipantsRequestFailed(ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionRemoveParticipantsRequestFailed(ImsCallSession.this,
                        reasonInfo);
            }
        }

        /**
         * Notifies the changes of the conference info. in the conference session.
         */
        @Override
        public void callSessionConferenceStateUpdated(ImsConferenceState state) {
            if (mListener != null) {
                mListener.callSessionConferenceStateUpdated(ImsCallSession.this, state);
            }
        }

        /**
         * Notifies the incoming USSD message.
         */
        @Override
        public void callSessionUssdMessageReceived(int mode, String ussdMessage) {
            if (mListener != null) {
                mListener.callSessionUssdMessageReceived(ImsCallSession.this, mode, ussdMessage);
            }
        }

        /**
         * Notifies of a case where a {@link ImsCallSession} may
         * potentially handover from one radio technology to another.
         * @param srcAccessTech The source radio access technology; one of the access technology
         *                      constants defined in {@link android.telephony.ServiceState}.  For
         *                      example
         *                      {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
         * @param targetAccessTech The target radio access technology; one of the access technology
         *                      constants defined in {@link android.telephony.ServiceState}.  For
         *                      example
         *                      {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
         */
        @Override
        public void callSessionMayHandover(int srcAccessTech, int targetAccessTech) {
            if (mListener != null) {
                mListener.callSessionMayHandover(ImsCallSession.this, srcAccessTech,
                        targetAccessTech);
            }
        }

        /**
         * Notifies of handover information for this call
         */
        @Override
        public void callSessionHandover(int srcAccessTech, int targetAccessTech,
                ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionHandover(ImsCallSession.this, srcAccessTech,
                        targetAccessTech, reasonInfo);
            }
        }

        /**
         * Notifies of handover failure info for this call
         */
        @Override
        public void callSessionHandoverFailed(int srcAccessTech, int targetAccessTech,
                ImsReasonInfo reasonInfo) {
            if (mListener != null) {
                mListener.callSessionHandoverFailed(ImsCallSession.this, srcAccessTech,
                        targetAccessTech, reasonInfo);
            }
        }

        /**
         * Notifies the TTY mode received from remote party.
         */
        @Override
        public void callSessionTtyModeReceived(int mode) {
            if (mListener != null) {
                mListener.callSessionTtyModeReceived(ImsCallSession.this, mode);
            }
        }

        /**
         * Notifies of a change to the multiparty state for this {@code ImsCallSession}.
         *
         * @param isMultiParty {@code true} if the session became multiparty, {@code false}
         *      otherwise.
         */
        public void callSessionMultipartyStateChanged(boolean isMultiParty) {
            if (mListener != null) {
                mListener.callSessionMultipartyStateChanged(ImsCallSession.this, isMultiParty);
            }
        }

        @Override
        public void callSessionSuppServiceReceived(ImsSuppServiceNotification suppServiceInfo ) {
            if (mListener != null) {
                mListener.callSessionSuppServiceReceived(ImsCallSession.this, suppServiceInfo);
            }
        }

        /**
         * Received RTT modify request from remote party
         */
        @Override
        public void callSessionRttModifyRequestReceived(ImsCallProfile callProfile) {
            if (mListener != null) {
                mListener.callSessionRttModifyRequestReceived(ImsCallSession.this, callProfile);
            }
        }

        /**
         * Received response for RTT modify request
         */
        @Override
        public void callSessionRttModifyResponseReceived(int status) {
            if (mListener != null) {
                mListener.callSessionRttModifyResponseReceived(status);
            }
        }

        /**
         * RTT Message received
         */
        @Override
        public void callSessionRttMessageReceived(String rttMessage) {
            if (mListener != null) {
                mListener.callSessionRttMessageReceived(rttMessage);
            }
        }

        /**
         * While in call, there has been a change in RTT audio indicator.
         */
        @Override
        public void callSessionRttAudioIndicatorChanged(ImsStreamMediaProfile profile) {
            if (mListener != null) {
                mListener.callSessionRttAudioIndicatorChanged(profile);
            }
        }

        /**
         * Call quality updated
         */
        @Override
        public void callQualityChanged(CallQuality callQuality) {
            if (mListener != null) {
                mListener.callQualityChanged(callQuality);
            }
        }
    }

    /**
     * Provides a string representation of the {@link ImsCallSession}.  Primarily intended for
     * use in log statements.
     *
     * @return String representation of session.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ImsCallSession objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" state:");
        sb.append(State.toString(getState()));
        sb.append(" callId:");
        sb.append(getCallId());
        sb.append("]");
        return sb.toString();
    }
}
