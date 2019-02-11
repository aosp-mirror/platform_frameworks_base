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
 * limitations under the License.
 */
package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.WorkerThread;

/**
 * This class holds the delivery information of an {@link RcsOutgoingMessage} for each
 * {@link RcsParticipant} that the message was intended for.
 */
public class RcsOutgoingMessageDelivery {
    // The participant that this delivery is intended for
    private final int mRecipientId;
    // The message this delivery is associated with
    private final int mRcsOutgoingMessageId;

    /**
     * Constructor to be used with RcsOutgoingMessage.getDelivery()
     *
     * @hide
     */
    RcsOutgoingMessageDelivery(int recipientId, int messageId) {
        mRecipientId = recipientId;
        mRcsOutgoingMessageId = messageId;
    }

    /**
     * Sets the delivery time of this outgoing delivery and persists into storage.
     *
     * @param deliveredTimestamp The timestamp to set to delivery. It is defined as milliseconds
     *                           passed after midnight, January 1, 1970 UTC
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setDeliveredTimestamp(long deliveredTimestamp) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setOutgoingDeliveryDeliveredTimestamp(
                mRcsOutgoingMessageId, mRecipientId, deliveredTimestamp));
    }

    /**
     * @return Returns the delivered timestamp of the associated message to the associated
     * participant. Timestamp is defined as milliseconds passed after midnight, January 1, 1970 UTC.
     * Returns 0 if the {@link RcsOutgoingMessage} is not delivered yet.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getDeliveredTimestamp() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getOutgoingDeliveryDeliveredTimestamp(
                mRcsOutgoingMessageId, mRecipientId));
    }

    /**
     * Sets the seen time of this outgoing delivery and persists into storage.
     *
     * @param seenTimestamp The timestamp to set to delivery. It is defined as milliseconds
     *                      passed after midnight, January 1, 1970 UTC
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setSeenTimestamp(long seenTimestamp) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setOutgoingDeliverySeenTimestamp(
                mRcsOutgoingMessageId, mRecipientId, seenTimestamp));
    }

    /**
     * @return Returns the seen timestamp of the associated message by the associated
     * participant. Timestamp is defined as milliseconds passed after midnight, January 1, 1970 UTC.
     * Returns 0 if the {@link RcsOutgoingMessage} is not seen yet.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getSeenTimestamp() throws RcsMessageStoreException {
        return RcsControllerCall.call(
                iRcs -> iRcs.getOutgoingDeliverySeenTimestamp(mRcsOutgoingMessageId, mRecipientId));
    }

    /**
     * Sets the status of this outgoing delivery and persists into storage.
     *
     * @param status The status of the associated {@link RcsMessage}s delivery to the associated
     *               {@link RcsParticipant}
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setStatus(@RcsMessage.RcsMessageStatus int status) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setOutgoingDeliveryStatus(
                mRcsOutgoingMessageId, mRecipientId, status));
    }

    /**
     * @return Returns the status of this outgoing delivery.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public @RcsMessage.RcsMessageStatus int getStatus() throws RcsMessageStoreException {
        return RcsControllerCall.call(
                iRcs -> iRcs.getOutgoingDeliveryStatus(mRcsOutgoingMessageId, mRecipientId));
    }

    /**
     * @return Returns the recipient associated with this delivery.
     */
    @NonNull
    public RcsParticipant getRecipient() {
        return new RcsParticipant(mRecipientId);
    }

    /**
     * @return Returns the {@link RcsOutgoingMessage} associated with this delivery.
     */
    @NonNull
    public RcsOutgoingMessage getMessage() {
        return new RcsOutgoingMessage(mRcsOutgoingMessageId);
    }
}
