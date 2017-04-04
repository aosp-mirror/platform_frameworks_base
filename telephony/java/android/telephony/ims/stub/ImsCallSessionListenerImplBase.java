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

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsConferenceState;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.ImsSuppServiceNotification;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;

/**
 * Base implementation of ImsCallSessionListenerBase, which implements stub versions of the methods
 * in the IImsCallSessionListener AIDL. Override the methods that your implementation of
 * ImsCallSessionListener supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsCallSessionListener maintained by other ImsServices.
 *
 * @hide
 */
public class ImsCallSessionListenerImplBase extends IImsCallSessionListener.Stub {
    /**
     * Notifies the result of the basic session operation (setup / terminate).
     */
    @Override
    public void callSessionProgressing(IImsCallSession session, ImsStreamMediaProfile profile) {
        // no-op
    }

    @Override
    public void callSessionStarted(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionStartFailed(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionTerminated(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    /**
     * Notifies the result of the call hold/resume operation.
     */
    @Override
    public void callSessionHeld(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionHoldFailed(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionHoldReceived(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionResumed(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionResumeFailed(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionResumeReceived(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    /**
     * Notifies the result of call merge operation.
     */
    @Override
    public void callSessionMergeStarted(IImsCallSession session, IImsCallSession newSession,
            ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionMergeComplete(IImsCallSession session) {
        // no-op
    }

    @Override
    public void callSessionMergeFailed(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    /**
     * Notifies the result of call upgrade / downgrade or any other call
     * updates.
     */
    @Override
    public void callSessionUpdated(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionUpdateFailed(IImsCallSession session, ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionUpdateReceived(IImsCallSession session, ImsCallProfile profile) {
        // no-op
    }

    /**
     * Notifies the result of conference extension.
     */
    @Override
    public void callSessionConferenceExtended(IImsCallSession session, IImsCallSession newSession,
            ImsCallProfile profile) {
        // no-op
    }

    @Override
    public void callSessionConferenceExtendFailed(IImsCallSession session,
            ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionConferenceExtendReceived(IImsCallSession session,
            IImsCallSession newSession,
            ImsCallProfile profile) {
        // no-op
    }

    /**
     * Notifies the result of the participant invitation / removal to/from the
     * conference session.
     */
    @Override
    public void callSessionInviteParticipantsRequestDelivered(IImsCallSession session) {
        // no-op
    }

    @Override
    public void callSessionInviteParticipantsRequestFailed(IImsCallSession session,
            ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionRemoveParticipantsRequestDelivered(IImsCallSession session) {
        // no-op
    }

    @Override
    public void callSessionRemoveParticipantsRequestFailed(IImsCallSession session,
            ImsReasonInfo reasonInfo) {
        // no-op
    }

    /**
     * Notifies the changes of the conference info. the conference session.
     */
    @Override
    public void callSessionConferenceStateUpdated(IImsCallSession session,
            ImsConferenceState state) {
        // no-op
    }

    /**
     * Notifies the incoming USSD message.
     */
    @Override
    public void callSessionUssdMessageReceived(IImsCallSession session, int mode,
            String ussdMessage) {
        // no-op
    }

    /**
     * Notifies of handover information for this call
     */
    @Override
    public void callSessionHandover(IImsCallSession session, int srcAccessTech,
            int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        // no-op
    }

    @Override
    public void callSessionHandoverFailed(IImsCallSession session, int srcAccessTech,
            int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        // no-op
    }

    /**
     * Notifies the TTY mode change by remote party.
     *
     * @param mode one of the following: -
     *            {@link com.android.internal.telephony.Phone#TTY_MODE_OFF} -
     *            {@link com.android.internal.telephony.Phone#TTY_MODE_FULL} -
     *            {@link com.android.internal.telephony.Phone#TTY_MODE_HCO} -
     *            {@link com.android.internal.telephony.Phone#TTY_MODE_VCO}
     */
    @Override
    public void callSessionTtyModeReceived(IImsCallSession session, int mode) {
        // no-op
    }

    /**
     * Notifies of a change to the multiparty state for this
     * {@code ImsCallSession}.
     *
     * @param session The call session.
     * @param isMultiParty {@code true} if the session became multiparty,
     *            {@code false} otherwise.
     */
    @Override
    public void callSessionMultipartyStateChanged(IImsCallSession session, boolean isMultiParty) {
        // no-op
    }

    /**
     * Notifies the supplementary service information for the current session.
     */
    @Override
    public void callSessionSuppServiceReceived(IImsCallSession session,
            ImsSuppServiceNotification suppSrvNotification) {
        // no-op
    }

    /**
     * Received RTT modify request from Remote Party
     * @param session The call session.
     * @param callProfile ImsCallProfile with updated attribute
     */
    @Override
    public void callSessionRttModifyRequestReceived(IImsCallSession session,
            ImsCallProfile callProfile) {
        // no-op
    }

    /**
     * Received response for RTT modify request
     * @param status true : Accepted the request
     *               false : Declined the request
     */
    @Override
    public void callSessionRttModifyResponseReceived(int status) {
        // no -op
    }

    /**
     * Device received RTT message from Remote UE
     * @param rttMessage RTT message received
     */
    @Override
    public void callSessionRttMessageReceived(String rttMessage) {
        // no-op
    }
}

