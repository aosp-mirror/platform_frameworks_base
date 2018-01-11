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

import android.os.RemoteException;
import android.telephony.ims.aidl.IImsCallSessionListener;

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsConferenceState;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.ImsSuppServiceNotification;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.ImsCallSession;

/**
 * Proxy class for interfacing with the framework's Call session for an ongoing IMS call.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsCallSessionListener maintained by other ImsServices.
 *
 * @hide
 */
public class ImsCallSessionListener {

    private final IImsCallSessionListener mListener;

    public ImsCallSessionListener(IImsCallSessionListener l) {
        mListener = l;
    }

    /**
     * Called when a request is sent out to initiate a new session
     * and 1xx response is received from the network.
     */
    public void callSessionProgressing(ImsStreamMediaProfile profile)
            throws RemoteException {
        mListener.callSessionProgressing(profile);
    }

    /**
     * Called when the session is initiated.
     *
     * @param profile the associated {@link ImsCallSession}.
     */
    public void callSessionInitiated(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionInitiated(profile);
    }

    /**
     * Called when the session establishment has failed.
     *
     * @param reasonInfo detailed reason of the session establishment failure
     */
    public void callSessionInitiatedFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionInitiatedFailed(reasonInfo);
    }

    /**
     * Called when the session is terminated.
     *
     * @param reasonInfo detailed reason of the session termination
     */
    public void callSessionTerminated(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionTerminated(reasonInfo);
    }

    /**
     * Called when the session is on hold.
     */
    public void callSessionHeld(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionHeld(profile);
    }

    /**
     * Called when the session hold has failed.
     *
     * @param reasonInfo detailed reason of the session hold failure
     */
    public void callSessionHoldFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionHoldFailed(reasonInfo);
    }

    /**
     * Called when the session hold is received from the remote user.
     */
    public void callSessionHoldReceived(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionHoldReceived(profile);
    }

    /**
     * Called when the session resume is done.
     */
    public void callSessionResumed(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionResumed(profile);
    }

    /**
     * Called when the session resume has failed.
     *
     * @param reasonInfo detailed reason of the session resume failure
     */
    public void callSessionResumeFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionResumeFailed(reasonInfo);
    }

    /**
     * Called when the session resume is received from the remote user.
     */
    public void callSessionResumeReceived(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionResumeReceived(profile);
    }

    /**
     * Called when the session merge has been started.  At this point, the {@code newSession}
     * represents the session which has been initiated to the IMS conference server for the
     * new merged conference.
     *
     * @param newSession the session object that is merged with an active & hold session
     */
    public void callSessionMergeStarted(ImsCallSession newSession, ImsCallProfile profile)
            throws RemoteException {
        mListener.callSessionMergeStarted(newSession != null ? newSession.getSession() : null,
                profile);
    }

    /**
     * Called when the session merge has been started.  At this point, the {@code newSession}
     * represents the session which has been initiated to the IMS conference server for the
     * new merged conference.
     *
     * @param newSession the session object that is merged with an active & hold session
     *
     * @hide
     */
    public void callSessionMergeStarted(IImsCallSession newSession, ImsCallProfile profile)
            throws RemoteException {
        mListener.callSessionMergeStarted(newSession, profile);
    }

    /**
     * Called when the session merge is successful and the merged session is active.
     *
     * @param newSession the new session object that is used for the conference
     */
    public void callSessionMergeComplete(ImsCallSession newSession) throws RemoteException {
        mListener.callSessionMergeComplete(newSession != null ? newSession.getSession() : null);
    }

    /**
     * Called when the session merge is successful and the merged session is active.
     *
     * @param newSession the new session object that is used for the conference
     *
     * @hide
     */
    public void callSessionMergeComplete(IImsCallSession newSession) throws RemoteException {
        mListener.callSessionMergeComplete(newSession);
    }

    /**
     * Called when the session merge has failed.
     *
     * @param reasonInfo detailed reason of the call merge failure
     */
    public void callSessionMergeFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionMergeFailed(reasonInfo);
    }

    /**
     * Called when the session is updated (except for hold/unhold).
     */
    public void callSessionUpdated(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionUpdated(profile);
    }

    /**
     * Called when the session update has failed.
     *
     * @param reasonInfo detailed reason of the session update failure
     */
    public void callSessionUpdateFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionUpdateFailed(reasonInfo);
    }

    /**
     * Called when the session update is received from the remote user.
     */
    public void callSessionUpdateReceived(ImsCallProfile profile) throws RemoteException {
        mListener.callSessionUpdateReceived(profile);
    }

    /**
     * Called when the session has been extended to a conference session.
     *
     * @param newSession the session object that is extended to the conference
     *      from the active session
     */
    public void callSessionConferenceExtended(ImsCallSession newSession, ImsCallProfile profile)
            throws RemoteException {
        mListener.callSessionConferenceExtended(newSession != null ? newSession.getSession() : null,
                profile);
    }

    /**
     * Called when the session has been extended to a conference session.
     *
     * @param newSession the session object that is extended to the conference
     *      from the active session
     * @hide
     */
    public void callSessionConferenceExtended(IImsCallSession newSession, ImsCallProfile profile)
            throws RemoteException {
        mListener.callSessionConferenceExtended(newSession, profile);
    }

    /**
     * Called when the conference extension has failed.
     *
     * @param reasonInfo detailed reason of the conference extension failure
     */
    public void callSessionConferenceExtendFailed(ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionConferenceExtendFailed(reasonInfo);
    }

    /**
     * Called when the conference extension is received from the remote user.
     */
    public void callSessionConferenceExtendReceived(ImsCallSession newSession,
            ImsCallProfile profile) throws RemoteException {
        mListener.callSessionConferenceExtendReceived(newSession != null
                ? newSession.getSession() : null, profile);
    }

    /**
     * Called when the conference extension is received from the remote user.
     *
     * @hide
     */
    public void callSessionConferenceExtendReceived(IImsCallSession newSession,
            ImsCallProfile profile) throws RemoteException {
        mListener.callSessionConferenceExtendReceived(newSession, profile);
    }

    /**
     * Called when the invitation request of the participants is delivered to the conference
     * server.
     */
    public void callSessionInviteParticipantsRequestDelivered() throws RemoteException {
        mListener.callSessionInviteParticipantsRequestDelivered();
    }

    /**
     * Called when the invitation request of the participants has failed.
     *
     * @param reasonInfo detailed reason of the conference invitation failure
     */
    public void callSessionInviteParticipantsRequestFailed(ImsReasonInfo reasonInfo)
            throws RemoteException {
        mListener.callSessionInviteParticipantsRequestFailed(reasonInfo);
    }

    /**
     * Called when the removal request of the participants is delivered to the conference
     * server.
     */
    public void callSessionRemoveParticipantsRequestDelivered() throws RemoteException {
        mListener.callSessionRemoveParticipantsRequestDelivered();
    }

    /**
     * Called when the removal request of the participants has failed.
     *
     * @param reasonInfo detailed reason of the conference removal failure
     */
    public void callSessionRemoveParticipantsRequestFailed(ImsReasonInfo reasonInfo)
            throws RemoteException {
        mListener.callSessionInviteParticipantsRequestFailed(reasonInfo);
    }

    /**
     * Notifies the framework of the updated Call session conference state.
     *
     * @param state the new {@link ImsConferenceState} associated with the conference.
     */
    public void callSessionConferenceStateUpdated(ImsConferenceState state) throws RemoteException {
        mListener.callSessionConferenceStateUpdated(state);
    }

    /**
     * Notifies the incoming USSD message.
     */
    public void callSessionUssdMessageReceived(int mode, String ussdMessage)
            throws RemoteException {
        mListener.callSessionUssdMessageReceived(mode, ussdMessage);
    }

    /**
     * Notifies of a case where a {@link com.android.ims.internal.ImsCallSession} may potentially
     * handover from one radio technology to another.
     *
     * @param srcAccessTech    The source radio access technology; one of the access technology
     *                         constants defined in {@link android.telephony.ServiceState}.  For
     *                         example
     *                         {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
     * @param targetAccessTech The target radio access technology; one of the access technology
     *                         constants defined in {@link android.telephony.ServiceState}.  For
     *                         example
     *                         {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
     */
    public void callSessionMayHandover(int srcAccessTech, int targetAccessTech)
            throws RemoteException {
        mListener.callSessionMayHandover(srcAccessTech, targetAccessTech);
    }

    /**
     * Called when session access technology changes.
     *
     * @param srcAccessTech original access technology
     * @param targetAccessTech new access technology
     * @param reasonInfo
     */
    public void callSessionHandover(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionHandover(srcAccessTech, targetAccessTech, reasonInfo);
    }

    /**
     * Called when session access technology change fails.
     *
     * @param srcAccessTech original access technology
     * @param targetAccessTech new access technology
     * @param reasonInfo handover failure reason
     */
    public void callSessionHandoverFailed(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) throws RemoteException {
        mListener.callSessionHandoverFailed(srcAccessTech, targetAccessTech, reasonInfo);
    }

    /**
     * Called when the TTY mode is changed by the remote party.
     *
     * @param mode one of the following: -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_OFF} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_FULL} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_HCO} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    public void callSessionTtyModeReceived(int mode) throws RemoteException {
        mListener.callSessionTtyModeReceived(mode);
    }

    /**
     * Called when the multiparty state is changed for this {@code ImsCallSession}.
     *
     * @param isMultiParty {@code true} if the session became multiparty,
     *                     {@code false} otherwise.
     */

    public void callSessionMultipartyStateChanged(boolean isMultiParty) throws RemoteException {
        mListener.callSessionMultipartyStateChanged(isMultiParty);
    }

    /**
     * Called when the supplementary service information is received for the current session.
     */
    public void callSessionSuppServiceReceived(ImsSuppServiceNotification suppSrvNotification)
            throws RemoteException {
        mListener.callSessionSuppServiceReceived(suppSrvNotification);
    }

    /**
     * Received RTT modify request from the remote party.
     *
     * @param callProfile ImsCallProfile with updated attributes
     */
    public void callSessionRttModifyRequestReceived(ImsCallProfile callProfile)
            throws RemoteException {
        mListener.callSessionRttModifyRequestReceived(callProfile);
    }

    /**
     * @param status the received response for RTT modify request.
     */
    public void callSessionRttModifyResponseReceived(int status) throws RemoteException {
        mListener.callSessionRttModifyResponseReceived(status);
    }

    /**
     * Device received RTT message from Remote UE.
     *
     * @param rttMessage RTT message received
     */
    public void callSessionRttMessageReceived(String rttMessage) throws RemoteException {
        mListener.callSessionRttMessageReceived(rttMessage);
    }
}

