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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.telephony.Annotation;
import android.telephony.CallQuality;
import android.telephony.ServiceState;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsCallSessionImplBase.MediaStreamDirection;
import android.telephony.ims.stub.ImsCallSessionImplBase.MediaStreamType;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

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
    private static final String TAG = "ImsCallSessionListener";
    private final IImsCallSessionListener mListener;

    /** @hide */
    public ImsCallSessionListener(IImsCallSessionListener l) {
        mListener = l;
    }

    /**
     * Called when the network first begins to establish the call session and is now connecting
     * to the remote party. This must be called once after {@link ImsCallSessionImplBase#start} and
     * before any other method on this listener.  After this is called,
     * {@link #callSessionProgressing(ImsStreamMediaProfile)} must be called to communicate any
     * further updates.
     * <p/>
     * Once this is called, {@link #callSessionTerminated} must be called
     * to end the call session.  In the event that the session failed before the remote party
     * was contacted, {@link #callSessionInitiatingFailed} must be called.
     *
     * @param profile the associated {@link ImsCallProfile}.
     */
    public void callSessionInitiating(@NonNull ImsCallProfile profile) {
        try {
            mListener.callSessionInitiating(profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * The IMS call session establishment has failed while initiating.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason of the IMS call session
     * establishment failure.
     */
    public void callSessionInitiatingFailed(@NonNull ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionInitiatingFailed(reasonInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Called after the network has contacted the remote party and the call state should move to
     * ALERTING.
     *
     * @param profile the associated {@link ImsCallProfile}.
     */
    public void callSessionProgressing(ImsStreamMediaProfile profile) {
        try {
            mListener.callSessionProgressing(profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Called once the outgoing IMS call session has been begun between the local and remote party.
     * The call state should move to ACTIVE.
     *
     * @param profile the associated {@link ImsCallProfile}.
     */
    public void callSessionInitiated(ImsCallProfile profile) {
        try {
            mListener.callSessionInitiated(profile);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * The IMS call session establishment has failed.
     *
     * @param reasonInfo {@link ImsReasonInfo} detailing the reason of the IMS call session
     * establishment failure.
     * @deprecated {@link #callSessionInitiated(ImsCallProfile)} is called immediately after
     * the session is first started which meant that there was no time in which a call to this
     * method was technically valid.  This method is replaced starting Android S in favor of
     * {@link #callSessionInitiatingFailed(ImsReasonInfo)}.
     */
    @Deprecated
    public void callSessionInitiatedFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionInitiatedFailed(reasonInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
     * @deprecated Uses hidden constants for radio access technology, use
     * {@link #onMayHandover(int, int)} instead.
     */
    @Deprecated
    public void callSessionMayHandover(int srcAccessTech, int targetAccessTech) {
        // Use new API internally.
        onMayHandover(ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech));
    }

    /**
     * Notify the framework that the associated {@link ImsCallSession} may handover from one network
     * type to another.
     *
     * @param srcNetworkType The source network type.
     * @param targetNetworkType The target network type.
     */
    public void onMayHandover(@Annotation.NetworkType int srcNetworkType,
            @Annotation.NetworkType int targetNetworkType) {
        try {
            mListener.callSessionMayHandover(srcNetworkType, targetNetworkType);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
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
     * @deprecated Uses hidden radio access technology constants, use
     * {@link #onHandover(int, int, ImsReasonInfo)} instead.
     */
    @Deprecated
    public void callSessionHandover(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        // Use new API internally.
        onHandover(ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech), reasonInfo);
    }

    /**
     * Notify the framework that the associated {@link ImsCallSession} has handed over from one
     * network type to another.
     *
     * @param srcNetworkType original network type.
     * @param targetNetworkType target network type after handover..
     * @param reasonInfo An optional {@link ImsReasonInfo} associated with this handover.
     */
    public void onHandover(@Annotation.NetworkType int srcNetworkType,
            @Annotation.NetworkType int targetNetworkType, @Nullable ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionHandover(srcNetworkType, targetNetworkType, reasonInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * The IMS call session's access technology change has failed..
     *
     * @param srcAccessTech original access technology
     * @param targetAccessTech new access technology
     * @param reasonInfo An {@link ImsReasonInfo} detailing the reason for the failure.
     * @deprecated Uses hidden radio access technology constants, use
     * {@link #onHandoverFailed(int, int, ImsReasonInfo)} instead
     */
    @Deprecated
    public void callSessionHandoverFailed(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        // Use new API internally.
        onHandoverFailed(ServiceState.rilRadioTechnologyToNetworkType(srcAccessTech),
                ServiceState.rilRadioTechnologyToNetworkType(targetAccessTech), reasonInfo);
    }

    /**
     * The IMS call session's access technology change has failed..
     *
     * @param srcNetworkType original network type.
     * @param targetNetworkType target network type that the handover failed for.
     * @param reasonInfo An {@link ImsReasonInfo} detailing the reason for the failure.
     */
    public void onHandoverFailed(@Annotation.NetworkType int srcNetworkType,
            @Annotation.NetworkType int targetNetworkType, @NonNull ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionHandoverFailed(srcNetworkType, targetNetworkType, reasonInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
        }
    }

    /**
     * The {@link ImsService} calls this method to inform the framework of a DTMF digit which was
     * received from the network.
     * <p>
     * According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833 sec 3.10</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15.
     * <p>
     * <em>Note:</em> Alpha DTMF digits are converted from lower-case to upper-case.
     *
     * @param dtmf The DTMF digit received, '0'-'9', *, #, A, B, C, or D.
     * @throws IllegalArgumentException If an invalid DTMF character is provided.
     */
    public void callSessionDtmfReceived(char dtmf) {
        if (!(dtmf >= '0' && dtmf <= '9'
                || dtmf >= 'A' && dtmf <= 'D'
                || dtmf >= 'a' && dtmf <= 'd'
                || dtmf == '*'
                || dtmf == '#')) {
            throw new IllegalArgumentException("DTMF digit must be 0-9, *, #, A, B, C, D");
        }
        try {
            mListener.callSessionDtmfReceived(Character.toUpperCase(dtmf));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * The {@link ImsService} calls this method to inform the framework of RTP header extension data
     * which was received from the network.
     * <p>
     * The set of {@link RtpHeaderExtension} data are identified by local identifiers which were
     * negotiated during SDP signalling.  See RFC8285,
     * {@link ImsCallProfile#getAcceptedRtpHeaderExtensionTypes()} and
     * {@link RtpHeaderExtensionType} for more information.
     * <p>
     * By specification, the RTP header extension is an unacknowledged transmission and there is no
     * guarantee that the header extension will be delivered by the network to the other end of the
     * call.
     *
     * @param extensions The RTP header extension data received.
     */
    public void callSessionRtpHeaderExtensionsReceived(
            @NonNull Set<RtpHeaderExtension> extensions) {
        Objects.requireNonNull(extensions, "extensions are required.");
        try {
            mListener.callSessionRtpHeaderExtensionsReceived(
                    new ArrayList<RtpHeaderExtension>(extensions));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the result of transfer request.
     * @hide
     */
    public void callSessionTransferred() {
        try {
            mListener.callSessionTransferred();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the result of transfer request.
     *
     * @param reasonInfo {@link ImsReasonInfo} containing a reason for the
     * session transfer failure
     * @hide
     */
    public void callSessionTransferFailed(ImsReasonInfo reasonInfo) {
        try {
            mListener.callSessionTransferFailed(reasonInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Access Network Bitrate Recommendation Query (ANBRQ), see 3GPP TS 26.114.
     * This API triggers radio to send ANBRQ message to the access network to query the
     * desired bitrate.
     *
     * @param mediaType {@link ImsCallSessionImplBase.MediaStreamType} is used to identify
     *        media stream such as audio or video.
     * @param direction {@link ImsCallSessionImplBase.MediaStreamDirection} of this packet
     *        stream (e.g. uplink or downlink).
     * @param bitsPerSecond This value is the bitrate requested by the other party UE through
     *        RTP CMR, RTCPAPP or TMMBR, and ImsStack converts this value to the MAC bitrate
     *        (defined in TS36.321, range: 0 ~ 8000 kbit/s).
     * @hide
     */
    public final void callSessionSendAnbrQuery(@MediaStreamType int mediaType,
                @MediaStreamDirection int direction, @IntRange(from = 0) int bitsPerSecond) {
        Log.d(TAG, "callSessionSendAnbrQuery in imscallsessonListener");
        try {
            mListener.callSessionSendAnbrQuery(mediaType, direction, bitsPerSecond);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}

