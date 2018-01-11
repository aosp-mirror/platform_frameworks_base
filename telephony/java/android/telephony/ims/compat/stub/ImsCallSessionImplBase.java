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

import android.os.RemoteException;
import android.telephony.ims.ImsCallSessionListener;

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsConferenceState;
import com.android.ims.ImsReasonInfo;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.ImsSuppServiceNotification;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.ImsCallSession;

/**
 * Compat implementation of ImsCallSessionImplBase for older implementations.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsCallSession maintained by other ImsServices.
 *
 * @hide
 */

public class ImsCallSessionImplBase extends android.telephony.ims.stub.ImsCallSessionImplBase {

    @Override
    public final void setListener(ImsCallSessionListener listener) {
        setListener(new ImsCallSessionListenerConverter(listener));
    }

    /**
     * Sets the listener to listen to the session events. An {@link ImsCallSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    public void setListener(IImsCallSessionListener listener) {

    }

    /**
     * There are two different ImsCallSessionListeners that need to reconciled here, we need to
     * convert the "old" version of the com.android.ims.internal.IImsCallSessionListener to the
     * "new" version of the Listener android.telephony.ims.ImsCallSessionListener when calling
     * back to the framework.
     */
    private class ImsCallSessionListenerConverter extends IImsCallSessionListener.Stub {

        private final ImsCallSessionListener mNewListener;

        public ImsCallSessionListenerConverter(ImsCallSessionListener listener) {
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
            mNewListener.callSessionHandover(srcAccessTech, targetAccessTech, reasonInfo);
        }

        @Override
        public void callSessionHandoverFailed(IImsCallSession i, int srcAccessTech,
                int targetAccessTech, ImsReasonInfo reasonInfo) throws RemoteException {
            mNewListener.callSessionHandoverFailed(srcAccessTech, targetAccessTech, reasonInfo);
        }

        @Override
        public void callSessionMayHandover(IImsCallSession i, int srcAccessTech, int targetAccessTech)
                throws RemoteException {
            mNewListener.callSessionMayHandover(srcAccessTech, targetAccessTech);
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
    }
}
