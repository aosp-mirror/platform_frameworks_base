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

import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.ImsCallProfile;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsConferenceState;
import com.android.ims.internal.IImsCallSession;

/**
 * A listener type for receiving notification on IMS call session events.
 * When an event is generated for an {@link IImsCallSession}, the application is notified
 * by having one of the methods called on the {@link IImsCallSessionListener}.
 * {@hide}
 */
interface IImsCallSessionListener {
    /**
     * Notifies the result of the basic session operation (setup / terminate).
     */
    void callSessionProgressing(in IImsCallSession session, in ImsStreamMediaProfile profile);
    void callSessionStarted(in IImsCallSession session, in ImsCallProfile profile);
    void callSessionStartFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    void callSessionTerminated(in IImsCallSession session, in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of the call hold/resume operation.
     */
    void callSessionHeld(in IImsCallSession session, in ImsCallProfile profile);
    void callSessionHoldFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    void callSessionHoldReceived(in IImsCallSession session, in ImsCallProfile profile);
    void callSessionResumed(in IImsCallSession session, in ImsCallProfile profile);
    void callSessionResumeFailed(in IImsCallSession session, in ImsReasonInfo reasonInfo);
    void callSessionResumeReceived(in IImsCallSession session, in ImsCallProfile profile);

    /**
     * Notifies the result of call merge operation.
     */
    void callSessionMergeStarted(in IImsCallSession session,
            in IImsCallSession newSession, in ImsCallProfile profile);
    void callSessionMergeComplete(in IImsCallSession session);
    void callSessionMergeFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);

    /**
     * Notifies the result of call upgrade / downgrade or any other call updates.
     */
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
    void callSessionInviteParticipantsRequestDelivered(in IImsCallSession session);
    void callSessionInviteParticipantsRequestFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);
    void callSessionRemoveParticipantsRequestDelivered(in IImsCallSession session);
    void callSessionRemoveParticipantsRequestFailed(in IImsCallSession session,
            in ImsReasonInfo reasonInfo);

    /**
     * Notifies the changes of the conference info. in the conference session.
     */
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
    void callSessionHandover(in IImsCallSession session,
            in int srcAccessTech, in int targetAccessTech, in ImsReasonInfo reasonInfo);
    void callSessionHandoverFailed(in IImsCallSession session,
            in int srcAccessTech, in int targetAccessTech, in ImsReasonInfo reasonInfo);

    /**
     * Notifies the TTY mode change by remote party.
     * @param mode one of the following:
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_OFF}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_FULL}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_HCO}
     * - {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    void callSessionTtyModeReceived(in IImsCallSession session, in int mode);

    /**
     * Notifies of a change to the multiparty state for this {@code ImsCallSession}.
     *
     * @param session The call session.
     * @param isMultiParty {@code true} if the session became multiparty, {@code false} otherwise.
     */
    void callSessionMultipartyStateChanged(in IImsCallSession session, in boolean isMultiParty);
}
