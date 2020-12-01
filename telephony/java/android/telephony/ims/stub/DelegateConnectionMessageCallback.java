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
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateManager;
import android.telephony.ims.SipMessage;

/**
 * The callback associated with a {@link SipDelegateConnection}, which handles newly received
 * messages as well as the result of sending a SIP message.
 * @hide
 */
@SystemApi
public interface DelegateConnectionMessageCallback {

    /**
     * A new {@link SipMessage} has been received from the delegate.
     * @param message the {@link SipMessage} routed to this RCS application.
     */
    void onMessageReceived(@NonNull SipMessage message);

    /**
     * A message previously sent to the SIP delegate using
     * {@link SipDelegateConnection#sendMessage} has been successfully sent.
     * @param viaTransactionId The transaction ID found in the via header field of the
     *                         previously sent {@link SipMessage}.
     */
    void onMessageSent(@NonNull String viaTransactionId);

    /**
     * A message previously sent to the SIP delegate using
     * {@link SipDelegateConnection#sendMessage} has failed to be sent.
     * @param viaTransactionId The Transaction ID found in the via header field of the
     *                         previously sent {@link SipMessage}.
     * @param reason The reason for the failure.
     */
    void onMessageSendFailure(@NonNull String viaTransactionId,
            @SipDelegateManager.MessageFailureReason int reason);
}
