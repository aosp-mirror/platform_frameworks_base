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

package android.telephony.ims.compat.stub;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.CallQuality;
import android.telephony.ServiceState;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsConferenceState;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsSuppServiceNotification;
import android.telephony.ims.aidl.IImsCallSessionListener;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsVideoCallProvider;

/**
 * Compat implementation of ImsCallSessionImplBase for older implementations.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsCallSession maintained by other ImsServices.
 *
 * @hide
 */

public class ImsCallSessionImplBase extends IImsCallSession.Stub {

    @UnsupportedAppUsage
    public ImsCallSessionImplBase() {
    }

    @Override
    // convert to old implementation of listener
    public final void setListener(IImsCallSessionListener listener)
            throws RemoteException {
        setListener(new ImsCallSessionListenerConverter(listener));
    }

    /**
     * Sets the listener to listen to the session events. An {@link ImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    public void setListener(com.android.ims.internal.IImsCallSessionListener listener) {

    }

    /**
     * Closes the object. This {@link ImsCallSessionImplBase} is not usable after being closed.
     */
    @Override
    public void close() {

    }

    /**
     * @return A String containing the unique call ID of this {@link ImsCallSessionImplBase}.
     */
    @Override
    public String getCallId() {
        return null;
    }

    /**
     * @return The {@link ImsCallProfile} that this {@link ImsCallSessionImplBase} is associated
     * with.
     */
    @Override
    public ImsCallProfile getCallProfile() {
        return null;
    }

    /**
     * @return The local {@link ImsCallProfile} that this {@link ImsCallSessionImplBase} is
     * associated with.
     */
    @Override
    public ImsCallProfile getLocalCallProfile() {
        return null;
    }

    /**
     * @return The remote {@link ImsCallProfile} that this {@link ImsCallSessionImplBase} is
     * associated with.
     */
    @Override
    public ImsCallProfile getRemoteCallProfile() {
        return null;
    }

    /**
     * @param name The String extra key.
     * @return The string extra value associated with the specified property.
     */
    @Override
    public String getProperty(String name) {
        return null;
    }

    /**
     * @return The {@link ImsCallSessionImplBase} state.
     */
    @Override
    public int getState() {
        return -1;
    }

    /**
     * @return true if the {@link ImsCallSessionImplBase} is in a call, false otherwise.
     */
    @Override
    public boolean isInCall() {
        return false;
    }

    /**
     * Mutes or unmutes the mic for the active call.
     *
     * @param muted true if the call should be muted, false otherwise.
     */
    @Override
    public void setMute(boolean muted) {
    }

    /**
     * Initiates an IMS call with the specified number and call profile.
     * The session listener set in {@link #setListener(IImsCallSessionListener)} is called back upon
     * defined session events.
     * Only valid to call when the session state is in
     * {@link ImsCallSession.State#IDLE}.
     *
     * @param callee dialed string to make the call to
     * @param profile call profile to make the call with the specified service type,
     *      call type and media information
     * @see {@link ImsCallSession.Listener#callSessionStarted},
     * {@link ImsCallSession.Listener#callSessionStartFailed}
     */
    @Override
    public void start(String callee, ImsCallProfile profile) {
    }

    /**
     * Initiates an IMS call with the specified participants and call profile.
     * The session listener set in {@link #setListener(IImsCallSessionListener)} is called back upon
     * defined session events.
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
    public void startConference(String[] participants, ImsCallProfile profile) {
    }

    /**
     * Accepts an incoming call or session update.
     *
     * @param callType call type specified in {@link ImsCallProfile} to be answered
     * @param profile stream media profile {@link ImsStreamMediaProfile} to be answered
     * @see {@link ImsCallSession.Listener#callSessionStarted}
     */
    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) {
    }

    /**
     * Deflects an incoming call.
     *
     * @param deflectNumber number to deflect the call
     */
    @Override
    public void deflect(String deflectNumber) {
    }

    /**
     * Transfer an established call to given number, disconnecting the ongoing call
     * when the transfer is complete.
     *
     * @param number number to transfer the call
     * @param isConfirmationRequired when {@code true}, then the {@link ImsCallSessionImplBase}
     * should wait until the transfer has successfully completed before disconnecting the current
     * {@link ImsCallSessionImplBase}. When {@code false}, the {@link ImsCallSessionImplBase}
     * should signal the network to perform the transfer, but should immediately disconnect the
     * call regardless of the outcome of the transfer.
     */
    @Override
    public void transfer(@NonNull String number, boolean isConfirmationRequired) {
    }

    /**
     * Transfer an established call to an existing ongoing session.
     * When the transfer is complete, the current call gets disconnected locally.
     */
    @Override
    public void consultativeTransfer(@NonNull IImsCallSession transferToSession) {
    }

    /**
     * Rejects an incoming call or session update.
     *
     * @param reason reason code to reject an incoming call, defined in {@link ImsReasonInfo}.
     * {@link ImsCallSession.Listener#callSessionStartFailed}
     */
    @Override
    public void reject(int reason) {
    }

    /**
     * Terminates a call.
     *
     * @param reason reason code to terminate a call, defined in {@link ImsReasonInfo}.
     *
     * @see {@link ImsCallSession.Listener#callSessionTerminated}
     */
    @Override
    public void terminate(int reason) {
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
    public void hold(ImsStreamMediaProfile profile) {
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
    public void resume(ImsStreamMediaProfile profile) {
    }

    /**
     * Merges the active and held call. When the merge starts,
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
    public void merge() {
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
    public void update(int callType, ImsStreamMediaProfile profile) {
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
    public void extendToConference(String[] participants) {
    }

    /**
     * Requests the conference server to invite an additional participants to the conference.
     *
     * @param participants participant list to be invited to the conference call
     * @see {@link ImsCallSession.Listener#callSessionInviteParticipantsRequestDelivered},
     *      {@link ImsCallSession.Listener#callSessionInviteParticipantsRequestFailed}
     */
    @Override
    public void inviteParticipants(String[] participants) {
    }

    /**
     * Requests the conference server to remove the specified participants from the conference.
     *
     * @param participants participant list to be removed from the conference call
     * @see {@link ImsCallSession.Listener#callSessionRemoveParticipantsRequestDelivered},
     *      {@link ImsCallSession.Listener#callSessionRemoveParticipantsRequestFailed}
     */
    @Override
    public void removeParticipants(String[] participants) {
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void sendDtmf(char c, Message result) {
    }

    /**
     * Start a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     */
    @Override
    public void startDtmf(char c) {
    }

    /**
     * Stop a DTMF code.
     */
    @Override
    public void stopDtmf() {
    }

    /**
     * Sends an USSD message.
     *
     * @param ussdMessage USSD message to send
     */
    @Override
    public void sendUssd(String ussdMessage) {
    }

    @Override
    public IImsVideoCallProvider getVideoCallProvider() {
        return null;
    }

    /**
     * Determines if the current session is multiparty.
     * @return {@code True} if the session is multiparty.
     */
    @Override
    public boolean isMultiparty() {
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
     * @param status true if the the request was accepted or false of the request is defined.
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

    /**
     * There are two different ImsCallSessionListeners that need to reconciled here, we need to
     * convert the "old" version of the com.android.ims.internal.IImsCallSessionListener to the
     * "new" version of the Listener android.telephony.ims.ImsCallSessionListener when calling
     * back to the framework.
     */
    private class ImsCallSessionListenerConverter
            extends com.android.ims.internal.IImsCallSessionListener.Stub {

        private final IImsCallSessionListener mNewListener;

        public ImsCallSessionListenerConverter(IImsCallSessionListener listener) {
            mNewListener = listener;
        }

        @Override
        public void callSessionProgressing(IImsCallSession i,
                ImsStreamMediaProfile imsStreamMediaProfile) throws RemoteException {
            mNewListener.callSessionProgressing(imsStreamMediaProfile);
        }

        @Override
        public void callSessionStarted(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionInitiated(imsCallProfile);
        }

        @Override
        public void callSessionStartFailed(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionInitiatedFailed(imsReasonInfo);
        }

        @Override
        public void callSessionTerminated(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionTerminated(imsReasonInfo);
        }

        @Override
        public void callSessionHeld(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionHeld(imsCallProfile);
        }

        @Override
        public void callSessionHoldFailed(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionHoldFailed(imsReasonInfo);
        }

        @Override
        public void callSessionHoldReceived(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionHoldReceived(imsCallProfile);
        }

        @Override
        public void callSessionResumed(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionResumed(imsCallProfile);
        }

        @Override
        public void callSessionResumeFailed(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionResumeFailed(imsReasonInfo);
        }

        @Override
        public void callSessionResumeReceived(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionResumeReceived(imsCallProfile);
        }

        @Override
        public void callSessionMergeStarted(IImsCallSession i, IImsCallSession newSession,
                ImsCallProfile profile)
                throws RemoteException {
            mNewListener.callSessionMergeStarted(newSession, profile);
        }

        @Override
        public void callSessionMergeComplete(IImsCallSession iImsCallSession)
                throws RemoteException {
            mNewListener.callSessionMergeComplete(iImsCallSession);
        }

        @Override
        public void callSessionMergeFailed(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionMergeFailed(imsReasonInfo);
        }

        @Override
        public void callSessionUpdated(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionUpdated(imsCallProfile);
        }

        @Override
        public void callSessionUpdateFailed(IImsCallSession i, ImsReasonInfo imsReasonInfo)
                throws RemoteException {
            mNewListener.callSessionUpdateFailed(imsReasonInfo);
        }

        @Override
        public void callSessionUpdateReceived(IImsCallSession i, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionUpdateReceived(imsCallProfile);
        }

        @Override
        public void callSessionConferenceExtended(IImsCallSession i, IImsCallSession newSession,
                ImsCallProfile imsCallProfile) throws RemoteException {
            mNewListener.callSessionConferenceExtended(newSession, imsCallProfile);
        }

        @Override
        public void callSessionConferenceExtendFailed(IImsCallSession i,
                ImsReasonInfo imsReasonInfo) throws RemoteException {
            mNewListener.callSessionConferenceExtendFailed(imsReasonInfo);
        }

        @Override
        public void callSessionConferenceExtendReceived(IImsCallSession i,
                IImsCallSession newSession, ImsCallProfile imsCallProfile)
                throws RemoteException {
            mNewListener.callSessionConferenceExtendReceived(newSession, imsCallProfile);
        }

        @Override
        public void callSessionInviteParticipantsRequestDelivered(IImsCallSession i)
                throws RemoteException {
            mNewListener.callSessionInviteParticipantsRequestDelivered();
        }

        @Override
        public void callSessionInviteParticipantsRequestFailed(IImsCallSession i,
                ImsReasonInfo imsReasonInfo) throws RemoteException {
            mNewListener.callSessionInviteParticipantsRequestFailed(imsReasonInfo);
        }

        @Override
        public void callSessionRemoveParticipantsRequestDelivered(IImsCallSession i)
                throws RemoteException {
            mNewListener.callSessionRemoveParticipantsRequestDelivered();
        }

        @Override
        public void callSessionRemoveParticipantsRequestFailed(IImsCallSession i,
                ImsReasonInfo imsReasonInfo) throws RemoteException {
            mNewListener.callSessionRemoveParticipantsRequestFailed(imsReasonInfo);
        }

        @Override
        public void callSessionConferenceStateUpdated(IImsCallSession i,
                ImsConferenceState imsConferenceState) throws RemoteException {
            mNewListener.callSessionConferenceStateUpdated(imsConferenceState);
        }

        @Override
        public void callSessionUssdMessageReceived(IImsCallSession i, int mode, String message)
                throws RemoteException {
            mNewListener.callSessionUssdMessageReceived(mode, message);
        }

        @Override
        public void callSessionHandover(IImsCallSession i, int srcAccessTech, int targetAccessTech,
                ImsReasonInfo reasonInfo) throws RemoteException {
            mNewListener.callSessionHandover(
                    ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                    ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech), reasonInfo);
        }

        @Override
        public void callSessionHandoverFailed(IImsCallSession i, int srcAccessTech,
                int targetAccessTech, ImsReasonInfo reasonInfo) throws RemoteException {
            mNewListener.callSessionHandoverFailed(
                    ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                    ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech), reasonInfo);
        }

        @Override
        public void callSessionMayHandover(IImsCallSession i, int srcAccessTech, int targetAccessTech)
                throws RemoteException {
            mNewListener.callSessionMayHandover(
                    ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                    ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech));
        }

        @Override
        public void callSessionTtyModeReceived(IImsCallSession iImsCallSession, int mode)
                throws RemoteException {
            mNewListener.callSessionTtyModeReceived(mode);
        }

        @Override
        public void callSessionMultipartyStateChanged(IImsCallSession i, boolean isMultiparty)
                throws RemoteException {
            mNewListener.callSessionMultipartyStateChanged(isMultiparty);
        }

        @Override
        public void callSessionSuppServiceReceived(IImsCallSession i,
                ImsSuppServiceNotification imsSuppServiceNotification) throws RemoteException {
            mNewListener.callSessionSuppServiceReceived(imsSuppServiceNotification);
        }

        @Override
        public void callSessionRttModifyRequestReceived(IImsCallSession i,
                ImsCallProfile imsCallProfile) throws RemoteException {
            mNewListener.callSessionRttModifyRequestReceived(imsCallProfile);
        }

        @Override
        public void callSessionRttModifyResponseReceived(int status) throws RemoteException {
            mNewListener.callSessionRttModifyResponseReceived(status);
        }

        @Override
        public void callSessionRttMessageReceived(String rttMessage) throws RemoteException {
            mNewListener.callSessionRttMessageReceived(rttMessage);
        }

        @Override
        public void callSessionRttAudioIndicatorChanged(ImsStreamMediaProfile profile)
                throws RemoteException {
            mNewListener.callSessionRttAudioIndicatorChanged(profile);
        }

        @Override
        public void callSessionTransferred() throws RemoteException {
            mNewListener.callSessionTransferred();
        }

        @Override
        public void callSessionTransferFailed(@Nullable ImsReasonInfo reasonInfo)
                throws RemoteException {
            mNewListener.callSessionTransferFailed(reasonInfo);
        }

        @Override
        public void callQualityChanged(CallQuality callQuality) throws RemoteException {
            mNewListener.callQualityChanged(callQuality);
        }
    }
}
