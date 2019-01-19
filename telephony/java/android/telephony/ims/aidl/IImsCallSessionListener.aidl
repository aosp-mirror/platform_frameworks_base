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

package android.telephony.ims.aidl;

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
    void callSessionProgressing(in ImsStreamMediaProfile profile);
    void callSessionInitiated(in ImsCallProfile profile);
    void callSessionInitiatedFailed(in ImsReasonInfo reasonInfo);
    void callSessionTerminated(in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of the call hold/resume operation.
     */
    void callSessionHeld(in ImsCallProfile profile);
    void callSessionHoldFailed(in ImsReasonInfo reasonInfo);
    void callSessionHoldReceived(in ImsCallProfile profile);
    void callSessionResumed(in ImsCallProfile profile);
    void callSessionResumeFailed(in ImsReasonInfo reasonInfo);
    void callSessionResumeReceived(in ImsCallProfile profile);

    /**
     * Notifies the result of call merge operation.
     */
    void callSessionMergeStarted(IImsCallSession newSession, in ImsCallProfile profile);
    void callSessionMergeComplete(IImsCallSession session);
    void callSessionMergeFailed(in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of call upgrade / downgrade or any other call updates.
     */
    void callSessionUpdated(in ImsCallProfile profile);
    void callSessionUpdateFailed(in ImsReasonInfo reasonInfo);
    void callSessionUpdateReceived(in ImsCallProfile profile);

    /**
     * Notifies the result of conference extension.
     */
    void callSessionConferenceExtended(IImsCallSession newSession, in ImsCallProfile profile);
    void callSessionConferenceExtendFailed(in ImsReasonInfo reasonInfo);
    void callSessionConferenceExtendReceived(IImsCallSession newSession,
            in ImsCallProfile profile);

    /**
     * Notifies the result of the participant invitation / removal to/from the conference session.
     */
    void callSessionInviteParticipantsRequestDelivered();
    void callSessionInviteParticipantsRequestFailed(in ImsReasonInfo reasonInfo);
    void callSessionRemoveParticipantsRequestDelivered();
    void callSessionRemoveParticipantsRequestFailed(in ImsReasonInfo reasonInfo);

    /**
     * Notifies the changes of the conference info. in the conference session.
     */
    void callSessionConferenceStateUpdated(in ImsConferenceState state);

    /**
     * Notifies the incoming USSD message.
     */
    void callSessionUssdMessageReceived(int mode, String ussdMessage);

    /**
     * Notifies of handover information for this call
     */
    void callSessionHandover(int srcAccessTech, int targetAccessTech,
            in ImsReasonInfo reasonInfo);
    void callSessionHandoverFailed(int srcAccessTech, int targetAccessTech,
            in ImsReasonInfo reasonInfo);
    void callSessionMayHandover(int srcAccessTech, int targetAccessTech);

    /**
     * Notifies the TTY mode change by remote party.
     * @param mode one of the following:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    void callSessionTtyModeReceived(int mode);

    /**
     * Notifies of a change to the multiparty state for this {@code ImsCallSession}.
     *
     * @param session The call session.
     * @param isMultiParty {@code true} if the session became multiparty, {@code false} otherwise.
     */
    void callSessionMultipartyStateChanged(boolean isMultiParty);

    /**
     * Notifies the supplementary service information for the current session.
     */
    void callSessionSuppServiceReceived(in ImsSuppServiceNotification suppSrvNotification);

    /**
     * Device received RTT modify request from Remote UE
     * @param session ImsCallProfile with updated attribute
     */
    void callSessionRttModifyRequestReceived(in ImsCallProfile callProfile);

    /**
     * Device issued RTT modify request and inturn received response
     * from Remote UE
     * @param status Will be one of the following values from:
     * - {@link Connection.RttModifyStatus}
     */
    void callSessionRttModifyResponseReceived(int status);

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
     * Notifies of a change to the call quality.
     * @param callQuality then updated call quality
     */
    void callQualityChanged(in CallQuality callQuality);
}
