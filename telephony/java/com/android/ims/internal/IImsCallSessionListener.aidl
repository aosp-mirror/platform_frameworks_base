/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims.internal;

import android.telephony.CallQuality;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsConferenceState;
import com.android.ims.internal.IImsCallSession;
import android.telephony.ims.ImsSuppServiceNotification;

/**
 * A listener type for receiving notification on IMS call session events.
 * When an event is generated for an {@link IImsCallSession}, the application is notified
 * by having one of the methods called on the {@link IImsCallSessionListener}.
 * {@hide}
 */
oneway interface IImsCallSessionListener {
    /**
     * Notifies the result of the basic session operation (setup / terminate).
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionProgressing(in IImsCallSession session, in ImsStreamMediaProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionStarted(in IImsCallSession session, in ImsCallProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionStartFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionTerminated(in IImsCallSession session, in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of the call hold/resume operation.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionHeld(in IImsCallSession session, in ImsCallProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionHoldFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionHoldReceived(in IImsCallSession session, in ImsCallProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionResumed(in IImsCallSession session, in ImsCallProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionResumeFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionResumeReceived(in IImsCallSession session, in ImsCallProfile profile);

    /**
     * Notifies the result of call merge operation.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionMergeStarted(in IImsCallSession session,
            in IImsCallSession newSession, in ImsCallProfile profile);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionMergeComplete(in IImsCallSession session);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionMergeFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of call upgrade / downgrade or any other call updates.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionUpdated(in IImsCallSession session,
            in ImsCallProfile profile);
    void callSessionUpdateFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);
    void callSessionUpdateReceived(in IImsCallSession session,
            in ImsCallProfile profile);

    /**
     * Notifies the result of conference extension.
     */
    void callSessionConferenceExtended(in IImsCallSession session,
            in IImsCallSession newSession, in ImsCallProfile profile);
    void callSessionConferenceExtendFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);
    void callSessionConferenceExtendReceived(in IImsCallSession session,
            in IImsCallSession newSession, in ImsCallProfile profile);

    /**
     * Notifies the result of the participant invitation / removal to/from the conference session.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionInviteParticipantsRequestDelivered(in IImsCallSession session);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionInviteParticipantsRequestFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);
    void callSessionRemoveParticipantsRequestDelivered(in IImsCallSession session);
    void callSessionRemoveParticipantsRequestFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);

    /**
     * Notifies the changes of the conference info. in the conference session.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionConferenceStateUpdated(in IImsCallSession session,
            in ImsConferenceState state);

    /**
     * Notifies the incoming USSD message.
     */
    void callSessionUssdMessageReceived(in IImsCallSession session,
            int mode, String ussdMessage);

    /**
     * Notifies of handover information for this call
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionHandover(in IImsCallSession session,
            in int srcAccessTech, in int targetAccessTech, in ImsReasonInfo reasonInfo);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionHandoverFailed(in IImsCallSession session,
            in int srcAccessTech, in int targetAccessTech, in ImsReasonInfo reasonInfo);
    void callSessionMayHandover(in IImsCallSession session,
            in int srcAccessTech, in int targetAccessTech);

    /**
     * Notifies the TTY mode change by remote party.
     * @param mode one of the following:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionTtyModeReceived(in IImsCallSession session, in int mode);

    /**
     * Notifies of a change to the multiparty state for this {@code ImsCallSession}.
     *
     * @param session The call session.
     * @param isMultiParty {@code true} if the session became multiparty, {@code false} otherwise.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionMultipartyStateChanged(in IImsCallSession session, in boolean isMultiParty);

    /**
     * Notifies the supplementary service information for the current session.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void callSessionSuppServiceReceived(in IImsCallSession session,
         in ImsSuppServiceNotification suppSrvNotification);

    /**
     * Device received RTT modify request from Remote UE
     * @param session ImsCallProfile with updated attribute
     */
    void callSessionRttModifyRequestReceived(in IImsCallSession session,
            in ImsCallProfile callProfile);

    /**
     * Device issued RTT modify request and inturn received response
     * from Remote UE
     * @param status Will be one of the following values from:
     * - {@link Connection.RttModifyStatus}
     */
    void callSessionRttModifyResponseReceived(in int status);

    /**
     * While in call, device received RTT message from Remote UE
     * @param rttMessage Received RTT message
     */
    void callSessionRttMessageReceived(in String rttMessage);

    /**
     * While in call, there has been a change in RTT audio indicator.
     * @param profile updated ImsStreamMediaProfile
     */
    void callSessionRttAudioIndicatorChanged(in ImsStreamMediaProfile profile);

    /**
     * Notifies about the response for call transfer request.
     */
    void callSessionTransferred();

    void callSessionTransferFailed(in ImsReasonInfo reasonInfo);
    /**
     * Notifies of a change to the call quality.
     * @param callQuality then updated call quality
     */
    void callQualityChanged(in CallQuality callQuality);

    /**
     * Access Network Bitrate Recommendation Query (ANBRQ), see 3GPP TS 26.114.
     * This API triggers radio to send ANBRQ message to the access network to query the desired
     * bitrate.
     *
     * @param mediaType MediaType is used to identify media stream such as audio or video.
     * @param direction Direction of this packet stream (e.g. uplink or downlink).
     * @param bitsPerSecond This value is the bitrate requested by the other party UE through
     *        RTP CMR, RTCPAPP or TMMBR, and ImsStack converts this value to the MAC bitrate
     *        (defined in TS36.321, range: 0 ~ 8000 kbit/s).
     */
    void callSessionSendAnbrQuery(int mediaType, int direction, int bitsPerSecond);
}
