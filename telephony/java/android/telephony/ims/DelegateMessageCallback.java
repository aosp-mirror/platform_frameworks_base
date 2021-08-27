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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.telephony.ims.stub.SipDelegate;

/**
 * Callback interface provided to the SipTransport implementation to notify a remote application of
 * the following:
 * <ul>
 *     <li>A new incoming SIP message associated with the feature tags the SipDelegate registered
 *     with has been received or an in-dialog request to this SipDelegate has been received.</li>
 *     <li>Acknowledge that an outgoing SIP message from the RCS application has been sent
 *     successfully or notify the application of the reason why it was not sent</li>
 * </ul>
 * @hide
 */
@SystemApi
public interface DelegateMessageCallback {

    /**
     * Sends a new incoming SIP message to the remote application for processing.
     */
    void onMessageReceived(@NonNull SipMessage message);

    /**
     * Notifies the remote application that a previous request to send a SIP message using
     * {@link SipDelegate#sendMessage} has succeeded.
     *
     * @param viaTransactionId The transaction ID found in the via header field of the
     *         previously sent {@link SipMessage}.
     */
    void onMessageSent(@NonNull String viaTransactionId);

    /**
     * Notifies the remote application that a previous request to send a SIP message using
     * {@link SipDelegate#sendMessage} has failed.
     *
     * @param viaTransactionId The Transaction ID found in the via header field of the previously
     *         sent {@link SipMessage}.
     * @param reason The reason for the failure.
     */
    void onMessageSendFailure(@NonNull String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}
