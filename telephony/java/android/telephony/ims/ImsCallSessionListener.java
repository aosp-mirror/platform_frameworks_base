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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.telephony.CallQuality;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.telephony.ims.stub.ImsCallSessionImplBase;

import com.android.ims.internal.IImsCallSession;

/**
 * Listener interface for notifying the Framework's {@link ImsCallSession} for updates to an ongoing
 * IMS call.
 *
 * @hide
 */
// DO NOT remove or change the existing APIs, only add new ones to this implementation or you
// will break other implementations of ImsCallSessionListener maintained by other ImsServices.
// TODO: APIs in here do not conform to API guidelines yet. This can be changed if
// ImsCallSessionListenerConverter is also changed.
@SystemApi
public class ImsCallSessionListener {

    private final IImsCallSessionListener mListener;

    /** @hide */
    public ImsCallSessionListener(IImsCallSessionListener l) {
        mListener = l;
    }

    /**
     * A request has been sent out to initiate a new IMS call session and a 1xx response has been
     * received from the network.
     */
    public void callSessionProgressing(ImsStreamMediaProfile profile) {
        try {
            mListener.callSessionProgressing(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has been initiated.
     *
     * @param profile the associated {@link ImsCallProfile}.
     */
    public void callSessionInitiated(ImsCallProfile profile) {
        try {
            mListener.callSessionInitiated(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session establishment has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason of the IMS call session
     * establishment failure.
     */
    public void callSessionInitiatedFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionInitiatedFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has been terminated.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason of the session termination.
     */
    public void callSessionTerminated(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionTerminated(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has started the process of holding the call. If it fails,
     * {@link #callSessionHoldFailed(ImsReasonInfo)} should be called.
     *
     * If the IMS call session is resumed, call {@link #callSessionResumed(ImsCallProfile)}.
     *
     * @param profile The associated {@link ImsCallProfile} of the call session that has been put
     * on hold.
     */
    public void callSessionHeld(ImsCallProfile profile) {
        try {
            mListener.callSessionHeld(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has failed to be held.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason of the session hold failure.
     */
    public void callSessionHoldFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionHoldFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This IMS Call session has been put on hold by the remote party.
     *
     * @param profile The {@link ImsCallProfile} associated with this IMS call session.
     */
    public void callSessionHoldReceived(ImsCallProfile profile) {
        try {
            mListener.callSessionHoldReceived(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has started the process of resuming the call. If the process of resuming
     * the call fails, call {@link #callSessionResumeFailed(ImsReasonInfo)}.
     *
     * @param profile The {@link ImsCallProfile} associated with this IMS call session.
     */
    public void callSessionResumed(ImsCallProfile profile) {
        try {
            mListener.callSessionResumed(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session resume has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} containing the detailed reason of the session resume
     * failure.
     */
    public void callSessionResumeFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionResumeFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The remote party has resumed this IMS call session.
     *
     * @param profile {@link ImsCallProfile} associated with the IMS call session.
     */
    public void callSessionResumeReceived(ImsCallProfile profile) {
        try {
            mListener.callSessionResumeReceived(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session merge has been started.  At this point, the {@code newSession}
     * represents the IMS call session which represents the new merged conference and has been
     * initiated to the IMS conference server.
     *
     * @param newSession the {@link ImsCallSessionImplBase} that represents the merged active & held
     * sessions.
     * @param profile The {@link ImsCallProfile} associated with this IMS call session.
     */
    public void callSessionMergeStarted(ImsCallSessionImplBase newSession, ImsCallProfile profile)
    {
        try {
            mListener.callSessionMergeStarted(newSession != null ?
                            newSession.getServiceImpl() : null, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compatibility method for older implementations.
     * See {@link #callSessionMergeStarted(ImsCallSessionImplBase, ImsCallProfile)}.
     *
     * @hide
     */
    public void callSessionMergeStarted(IImsCallSession newSession, ImsCallProfile profile)
    {
        try {
            mListener.callSessionMergeStarted(newSession, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The session merge is successful and the merged {@link ImsCallSession} is active.
     *
     * @param newSession the new {@link ImsCallSessionImplBase}
     *                  that represents the conference IMS call
     * session.
     */
    public void callSessionMergeComplete(ImsCallSessionImplBase newSession) {
        try {
            mListener.callSessionMergeComplete(newSession != null ?
                    newSession.getServiceImpl() : null);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compatibility method for older implementations of ImsService.
     *
     * See {@link #callSessionMergeComplete(ImsCallSessionImplBase)}}.
     *
     * @hide
     */
    public void callSessionMergeComplete(IImsCallSession newSession) {
        try {
            mListener.callSessionMergeComplete(newSession);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session merge has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} contining the reason for the call merge failure.
     */
    public void callSessionMergeFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionMergeFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session profile has been updated. Does not include holding or resuming a call.
     *
     * @param profile The {@link ImsCallProfile} associated with the updated IMS call session.
     */
    public void callSessionUpdated(ImsCallProfile profile) {
        try {
            mListener.callSessionUpdated(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session profile update has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} containing a reason for the session update failure.
     */
    public void callSessionUpdateFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionUpdateFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session profile has received an update from the remote user.
     *
     * @param profile The new {@link ImsCallProfile} associated with the update.
     */
    public void callSessionUpdateReceived(ImsCallProfile profile) {
        try {
            mListener.callSessionUpdateReceived(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Called when the session has been extended to a conference session.
     *
     * If the conference extension fails, call
     * {@link #callSessionConferenceExtendFailed(ImsReasonInfo)}.
     *
     * @param newSession the session object that is extended to the conference from the active
     * IMS Call session.
     * @param profile The {@link ImsCallProfile} associated with the IMS call session.
     */
    public void callSessionConferenceExtended(ImsCallSessionImplBase newSession,
            ImsCallProfile profile) {
        try {
            mListener.callSessionConferenceExtended(
                    newSession != null ? newSession.getServiceImpl() : null, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compatibility method to interface with older versions of ImsService.
     * See {@link #callSessionConferenceExtended(ImsCallSessionImplBase, ImsCallProfile)}.
     *
     * @hide
     */
    public void callSessionConferenceExtended(IImsCallSession newSession, ImsCallProfile profile) {
        try {
            mListener.callSessionConferenceExtended(newSession, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The previous conference extension has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} containing the detailed reason of the conference
     * extension failure.
     */
    public void callSessionConferenceExtendFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionConferenceExtendFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A conference extension has been received received from the remote party.
     *
     * @param newSession An {@link ImsCallSessionImplBase}
     *                   representing the extended IMS call session.
     * @param profile The {@link ImsCallProfile} associated with the new IMS call session.
     */
    public void callSessionConferenceExtendReceived(ImsCallSessionImplBase newSession,
            ImsCallProfile profile) {
        try {
            mListener.callSessionConferenceExtendReceived(newSession != null
                    ? newSession.getServiceImpl() : null, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Compatibility method to interface with older versions of ImsService.
     * See {@link #callSessionConferenceExtendReceived(ImsCallSessionImplBase, ImsCallProfile)}.
     *
     * @hide
     */
    public void callSessionConferenceExtendReceived(IImsCallSession newSession,
            ImsCallProfile profile) {
        try {
            mListener.callSessionConferenceExtendReceived(newSession, profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The request to invite participants to the conference has been delivered to the conference
     * server.
     */
    public void callSessionInviteParticipantsRequestDelivered() {
        try {
            mListener.callSessionInviteParticipantsRequestDelivered();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The previous request to invite participants to the conference (see
     * {@link #callSessionInviteParticipantsRequestDelivered()}) has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason forthe conference invitation
     * failure.
     */
    public void callSessionInviteParticipantsRequestFailed(ImsReasonInfo reasonInfo)
    {
        try {
            mListener.callSessionInviteParticipantsRequestFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The request to remove participants from the conference has been delivered to the conference
     * server.
     */
    public void callSessionRemoveParticipantsRequestDelivered() {
        try {
            mListener.callSessionRemoveParticipantsRequestDelivered();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The previous request to remove participants from the conference (see
     * {@link #callSessionRemoveParticipantsRequestDelivered()}) has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason for the conference removal
     * failure.
     */
    public void callSessionRemoveParticipantsRequestFailed(ImsReasonInfo reasonInfo)
    {
        try {
            mListener.callSessionInviteParticipantsRequestFailed(reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session's conference state has changed.
     *
     * @param state The new {@link ImsConferenceState} associated with the conference.
     */
    public void callSessionConferenceStateUpdated(ImsConferenceState state) {
        try {
            mListener.callSessionConferenceStateUpdated(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session has received a Ussd message.
     *
     * @param mode The mode of the USSD message, either
     * {@link ImsCallSessionImplBase#USSD_MODE_NOTIFY} or
     * {@link ImsCallSessionImplBase#USSD_MODE_REQUEST}.
     * @param ussdMessage The USSD message.
     */
    public void callSessionUssdMessageReceived(int mode, String ussdMessage)
    {
        try {
            mListener.callSessionUssdMessageReceived(mode, ussdMessage);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An {@link ImsCallSession} may potentially handover from one radio
     * technology to another.
     *
     * @param srcAccessTech The source radio access technology; one of the access technology
     * constants defined in {@link android.telephony.ServiceState}. For example
     * {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
     * @param targetAccessTech The target radio access technology; one of the access technology
     * constants defined in {@link android.telephony.ServiceState}. For example
     * {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE}.
     */
    public void callSessionMayHandover(int srcAccessTech, int targetAccessTech)
    {
        try {
            mListener.callSessionMayHandover(srcAccessTech, targetAccessTech);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session's access technology has changed.
     *
     * @param srcAccessTech original access technology, defined in
     * {@link android.telephony.ServiceState}.
     * @param targetAccessTech new access technology, defined in
     * {@link android.telephony.ServiceState}.
     * @param reasonInfo The {@link ImsReasonInfo} associated with this handover.
     */
    public void callSessionHandover(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionHandover(srcAccessTech, targetAccessTech, reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The IMS call session's access technology change has failed..
     *
     * @param srcAccessTech original access technology
     * @param targetAccessTech new access technology
     * @param reasonInfo An {@link ImsReasonInfo} detailing the reason for the failure.
     */
    public void callSessionHandoverFailed(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionHandoverFailed(srcAccessTech, targetAccessTech, reasonInfo);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The TTY mode has been changed by the remote party.
     *
     * @param mode one of the following: -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_OFF} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_FULL} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_HCO} -
     *             {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    public void callSessionTtyModeReceived(int mode) {
        try {
            mListener.callSessionTtyModeReceived(mode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The multiparty state has been changed for this {@code ImsCallSession}.
     *
     * @param isMultiParty {@code true} if the session became multiparty, {@code false} otherwise.
     */
    public void callSessionMultipartyStateChanged(boolean isMultiParty) {
        try {
            mListener.callSessionMultipartyStateChanged(isMultiParty);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Supplementary service information has been received for the current IMS call session.
     *
     * @param suppSrvNotification The {@link ImsSuppServiceNotification} containing the change.
     */
    public void callSessionSuppServiceReceived(ImsSuppServiceNotification suppSrvNotification)
    {
        try {
            mListener.callSessionSuppServiceReceived(suppSrvNotification);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An RTT modify request has been received from the remote party.
     *
     * @param callProfile An {@link ImsCallProfile} with the updated attributes
     */
    public void callSessionRttModifyRequestReceived(ImsCallProfile callProfile)
    {
        try {
            mListener.callSessionRttModifyRequestReceived(callProfile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An RTT modify response has been received.
     *
     * @param status the received response for RTT modify request.
     */
    public void callSessionRttModifyResponseReceived(int status) {
        try {
            mListener.callSessionRttModifyResponseReceived(status);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An RTT message has been received from the remote party.
     *
     * @param rttMessage The RTT message that has been received.
     */
    public void callSessionRttMessageReceived(String rttMessage) {
        try {
            mListener.callSessionRttMessageReceived(rttMessage);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * While in call, there has been a change in RTT audio indicator.
     *
     * @param profile updated ImsStreamMediaProfile
     */
    public void callSessionRttAudioIndicatorChanged(@NonNull ImsStreamMediaProfile profile) {
        try {
            mListener.callSessionRttAudioIndicatorChanged(profile);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The call quality has changed.
     *
     * @param callQuality The new call quality
     */
    public void callQualityChanged(@NonNull CallQuality callQuality) {
        try {
            mListener.callQualityChanged(callQuality);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}

