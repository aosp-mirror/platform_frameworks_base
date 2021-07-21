/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims.stub;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.telephony.ims.DelegateMessageCallback;
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.ImsService;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;

/**
 * The {@link SipDelegate} is implemented by the {@link ImsService} and allows a privileged
 * IMS application to use this delegate to send SIP messages as well as acknowledge the receipt of
 * incoming SIP messages delivered to the application over the existing IMS registration, allowing
 * for a single IMS registration for multiple IMS applications.
 * <p>
 * Once the SIP delegate is created for that application,
 * {@link ImsRegistrationImplBase#updateSipDelegateRegistration()} will be called, indicating that
 * the application is finished setting up SipDelegates and the existing IMS registration may be
 * modified to include the features managed by these SipDelegates.
 * <p>
 * This SipDelegate will need to notify the remote application of the registration of these features
 * as well as the associated {@link SipDelegateConfiguration} before the application can start
 * sending/receiving SIP messages via the transport. See
 * {@link android.telephony.ims.DelegateStateCallback} for more information.
 * @hide
 */
@SystemApi
public interface SipDelegate {

    /**
     * The framework calls this method when a remote RCS application wishes to send a new outgoing
     * SIP message.
     * <p>
     * Once sent, this SIP delegate should notify the remote application of the success or
     * failure using {@link DelegateMessageCallback#onMessageSent(String)} or
     * {@link DelegateMessageCallback#onMessageSendFailure(String, int)}.
     * @param message The SIP message to be sent over the operator’s network.
     * @param configVersion The SipDelegateImsConfiguration version used to construct the
     *         SipMessage. See {@link SipDelegateConfiguration} for more information. If the
     *         version specified here does not match the most recently constructed
     *         {@link SipDelegateConfiguration}, this message should fail validation checks and
     *         {@link DelegateMessageCallback#onMessageSendFailure} should be called with code
     *         {@link SipDelegateManager#MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION}.
     */
    void sendMessage(@NonNull SipMessage message, long configVersion);

    /**
     * The remote IMS application has closed a SIP session and the routing resources associated
     * with the SIP session using the provided Call-ID may now be cleaned up.
     * <p>
     * Typically, a SIP session will be considered closed when all associated dialogs receive a
     * BYE request. After the session has been closed, the IMS application will call
     * {@link SipDelegateConnection#cleanupSession(String)} to signal to the framework that
     * resources can be released. In some cases, the framework will request that the ImsService
     * close the session due to the open SIP session holding up an event such as applying a
     * provisioning change or handing over to another transport type. See
     * {@link DelegateRegistrationState}.
     *
     * @param callId The call-ID header value associated with the ongoing SIP Session that the
     *         framework is requesting be cleaned up.
     */
    void cleanupSession(@NonNull String callId);

    /**
     * The remote application has received the SIP message and is processing it.
     * @param viaTransactionId The Transaction ID found in the via header field of the
     *                         previously sent {@link SipMessage}.
     */
    void notifyMessageReceived(@NonNull String viaTransactionId);

    /**
     * The remote application has either not received the SIP message or there was an error
     * processing it.
     * @param viaTransactionId The Transaction ID found in the via header field of the
     *                         previously sent {@link SipMessage}.
     * @param reason The reason why the message was not correctly received.
     */
    void notifyMessageReceiveError(@NonNull String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}
