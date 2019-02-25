/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.WorkerThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a single instance of a message sent or received over RCS.
 *
 * @hide
 */
public abstract class RcsMessage {
    /**
     * The value to indicate that this {@link RcsMessage} does not have any location information.
     */
    public static final double LOCATION_NOT_SET = Double.MIN_VALUE;

    /**
     * The status to indicate that this {@link RcsMessage}s status is not set yet.
     */
    public static final int NOT_SET = 0;

    /**
     * The status to indicate that this {@link RcsMessage} is a draft and is not in the process of
     * sending yet.
     */
    public static final int DRAFT = 1;

    /**
     * The status to indicate that this {@link RcsMessage} was successfully sent.
     */
    public static final int QUEUED = 2;

    /**
     * The status to indicate that this {@link RcsMessage} is actively being sent.
     */
    public static final int SENDING = 3;

    /**
     * The status to indicate that this {@link RcsMessage} was successfully sent.
     */
    public static final int SENT = 4;

    /**
     * The status to indicate that this {@link RcsMessage} failed to send in an attempt before, and
     * now being retried.
     */
    public static final int RETRYING = 5;

    /**
     * The status to indicate that this {@link RcsMessage} has permanently failed to send.
     */
    public static final int FAILED = 6;

    /**
     * The status to indicate that this {@link RcsMessage} was successfully received.
     */
    public static final int RECEIVED = 7;

    /**
     * The status to indicate that this {@link RcsMessage} was seen.
     */
    public static final int SEEN = 9;

    /**
     * @hide
     */
    protected final int mId;

    @IntDef({
            DRAFT, QUEUED, SENDING, SENT, RETRYING, FAILED, RECEIVED, SEEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RcsMessageStatus {
    }

    RcsMessage(int id) {
        mId = id;
    }

    /**
     * Returns the row Id from the common message.
     *
     * @hide
     */
    public int getId() {
        return mId;
    }

    /**
     * @return Returns the subscription ID that this {@link RcsMessage} was sent from, or delivered
     * to.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     * @see android.telephony.SubscriptionInfo#getSubscriptionId
     */
    public int getSubscriptionId() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getMessageSubId(mId, isIncoming()));
    }

    /**
     * Sets the subscription ID that this {@link RcsMessage} was sent from, or delivered to and
     * persists it into storage.
     *
     * @param subId The subscription ID to persists into storage.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     * @see android.telephony.SubscriptionInfo#getSubscriptionId
     */
    @WorkerThread
    public void setSubscriptionId(int subId) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setMessageSubId(mId, isIncoming(), subId));
    }

    /**
     * Sets the status of this message and persists it into storage. Please see
     * {@link RcsFileTransferPart#setFileTransferStatus(int)} to set statuses around file transfers.
     *
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setStatus(@RcsMessageStatus int rcsMessageStatus) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setMessageStatus(mId, isIncoming(), rcsMessageStatus));
    }

    /**
     * @return Returns the status of this message. Please see
     * {@link RcsFileTransferPart#setFileTransferStatus(int)} to set statuses around file transfers.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public @RcsMessageStatus int getStatus() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getMessageStatus(mId, isIncoming()));
    }

    /**
     * Sets the origination timestamp of this message and persists it into storage. Origination is
     * defined as when the sender tapped the send button.
     *
     * @param timestamp The origination timestamp value in milliseconds passed after midnight,
     *                  January 1, 1970 UTC
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setOriginationTimestamp(long timestamp) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setMessageOriginationTimestamp(mId, isIncoming(), timestamp));
    }

    /**
     * @return Returns the origination timestamp of this message in milliseconds passed after
     * midnight, January 1, 1970 UTC. Origination is defined as when the sender tapped the send
     * button.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getOriginationTimestamp() throws RcsMessageStoreException {
        return RcsControllerCall.call(
                iRcs -> iRcs.getMessageOriginationTimestamp(mId, isIncoming()));
    }

    /**
     * Sets the globally unique RCS message identifier for this message and persists it into
     * storage. This function does not confirm that this message id is unique. Please see 4.4.5.2
     * - GSMA RCC.53 (RCS Device API 1.6 Specification
     *
     * @param rcsMessageGlobalId The globally RCS message identifier
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setRcsMessageId(String rcsMessageGlobalId) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setGlobalMessageIdForMessage(mId, isIncoming(), rcsMessageGlobalId));
    }

    /**
     * @return Returns the globally unique RCS message identifier for this message. Please see
     * 4.4.5.2 - GSMA RCC.53 (RCS Device API 1.6 Specification
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public String getRcsMessageId() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getGlobalMessageIdForMessage(mId, isIncoming()));
    }

    /**
     * @return Returns the user visible text included in this message.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public String getText() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getTextForMessage(mId, isIncoming()));
    }

    /**
     * Sets the user visible text for this message and persists in storage.
     *
     * @param text The text this message now has
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setText(String text) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setTextForMessage(mId, isIncoming(), text));
    }

    /**
     * @return Returns the associated latitude for this message, or
     * {@link RcsMessage#LOCATION_NOT_SET} if it does not contain a location.
     *
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public double getLatitude() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getLatitudeForMessage(mId, isIncoming()));
    }

    /**
     * Sets the latitude for this message and persists in storage.
     *
     * @param latitude The latitude for this location message.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setLatitude(double latitude) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setLatitudeForMessage(mId, isIncoming(), latitude));
    }

    /**
     * @return Returns the associated longitude for this message, or
     * {@link RcsMessage#LOCATION_NOT_SET} if it does not contain a location.
     *
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public double getLongitude() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getLongitudeForMessage(mId, isIncoming()));
    }

    /**
     * Sets the longitude for this message and persists in storage.
     *
     * @param longitude The longitude for this location message.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setLongitude(double longitude) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setLongitudeForMessage(mId, isIncoming(), longitude));
    }

    /**
     * Attaches an {@link RcsFileTransferPart} to this message and persists into storage.
     *
     * @param fileTransferCreationParameters The parameters to be used to create the
     *                                       {@link RcsFileTransferPart}
     * @return A new instance of {@link RcsFileTransferPart}
     * @throws RcsMessageStoreException if the file transfer could not be persisted into storage.
     */
    @NonNull
    @WorkerThread
    public RcsFileTransferPart insertFileTransfer(
            RcsFileTransferCreationParams fileTransferCreationParameters)
            throws RcsMessageStoreException {
        return new RcsFileTransferPart(RcsControllerCall.call(
                iRcs -> iRcs.storeFileTransfer(mId, isIncoming(), fileTransferCreationParameters)));
    }

    /**
     * @return Returns all the {@link RcsFileTransferPart}s associated with this message in an
     * unmodifiable set.
     * @throws RcsMessageStoreException if the file transfers could not be read from the storage
     */
    @NonNull
    @WorkerThread
    public Set<RcsFileTransferPart> getFileTransferParts() throws RcsMessageStoreException {
        Set<RcsFileTransferPart> fileTransferParts = new HashSet<>();

        int[] fileTransferIds = RcsControllerCall.call(
                iRcs -> iRcs.getFileTransfersAttachedToMessage(mId, isIncoming()));

        for (int fileTransfer : fileTransferIds) {
            fileTransferParts.add(new RcsFileTransferPart(fileTransfer));
        }

        return Collections.unmodifiableSet(fileTransferParts);
    }

    /**
     * Removes a {@link RcsFileTransferPart} from this message, and deletes it in storage.
     *
     * @param fileTransferPart The part to delete.
     * @throws RcsMessageStoreException if the file transfer could not be removed from storage
     */
    @WorkerThread
    public void removeFileTransferPart(@NonNull RcsFileTransferPart fileTransferPart)
            throws RcsMessageStoreException {
        if (fileTransferPart == null) {
            return;
        }

        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.deleteFileTransfer(fileTransferPart.getId()));
    }

    /**
     * @return Returns {@code true} if this message was received on this device, {@code false} if it
     * was sent.
     */
    public abstract boolean isIncoming();
}
