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

import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_1_TO_1;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_GROUP;

import android.annotation.NonNull;
import android.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;

/**
 * RcsThread represents a single RCS conversation thread. It holds messages that were sent and
 * received and events that occurred on that thread.
 *
 * @hide
 */
public abstract class RcsThread {
    /**
     * The rcs_participant_thread_id that represents this thread in the database
     *
     * @hide
     */
    protected int mThreadId;

    /**
     * @hide
     */
    protected final RcsControllerCall mRcsControllerCall;

    /**
     * @hide
     */
    protected RcsThread(RcsControllerCall rcsControllerCall, int threadId) {
        mThreadId = threadId;
        mRcsControllerCall = rcsControllerCall;
    }

    /**
     * @return Returns the summary of the latest message in this {@link RcsThread} packaged in an
     * {@link RcsMessageSnippet} object
     */
    @WorkerThread
    @NonNull
    public RcsMessageSnippet getSnippet() throws RcsMessageStoreException {
        return mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.getMessageSnippet(mThreadId, callingPackage));
    }

    /**
     * Adds a new {@link RcsIncomingMessage} to this RcsThread and persists it in storage.
     *
     * @throws RcsMessageStoreException if the message could not be persisted into storage.
     */
    @WorkerThread
    @NonNull
    public RcsIncomingMessage addIncomingMessage(
            @NonNull RcsIncomingMessageCreationParams rcsIncomingMessageCreationParams)
            throws RcsMessageStoreException {
        int messageId = mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.addIncomingMessage(mThreadId,
                        rcsIncomingMessageCreationParams, callingPackage));
        return new RcsIncomingMessage(mRcsControllerCall, messageId);
    }

    /**
     * Adds a new {@link RcsOutgoingMessage} to this RcsThread and persists it in storage.
     *
     * @throws RcsMessageStoreException if the message could not be persisted into storage.
     */
    @WorkerThread
    @NonNull
    public RcsOutgoingMessage addOutgoingMessage(
            @NonNull RcsOutgoingMessageCreationParams rcsOutgoingMessageCreationParams)
            throws RcsMessageStoreException {
        int messageId = mRcsControllerCall.call((iRcs, callingPackage) -> iRcs.addOutgoingMessage(
                mThreadId, rcsOutgoingMessageCreationParams, callingPackage));

        return new RcsOutgoingMessage(mRcsControllerCall, messageId);
    }

    /**
     * Deletes an {@link RcsMessage} from this RcsThread and updates the storage.
     *
     * @param rcsMessage The message to delete from the thread
     * @throws RcsMessageStoreException if the message could not be deleted
     */
    @WorkerThread
    public void deleteMessage(@NonNull RcsMessage rcsMessage) throws RcsMessageStoreException {
        mRcsControllerCall.callWithNoReturn(
                (iRcs, callingPackage) -> iRcs.deleteMessage(rcsMessage.getId(),
                        rcsMessage.isIncoming(), mThreadId,
                        isGroup(), callingPackage));
    }

    /**
     * Convenience function for loading all the {@link RcsMessage}s in this {@link RcsThread}. For
     * a more detailed and paginated query, please use
     * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)}
     *
     * @return Loads the {@link RcsMessage}s in this thread and returns them in an immutable list.
     * @throws RcsMessageStoreException if the messages could not be read from the storage
     */
    @WorkerThread
    @NonNull
    public RcsMessageQueryResult getMessages() throws RcsMessageStoreException {
        RcsMessageQueryParams queryParams =
                new RcsMessageQueryParams.Builder().setThread(this).build();
        return new RcsMessageQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getMessages(queryParams, callingPackage)));
    }

    /**
     * @return Returns whether this is a group thread or not
     */
    public abstract boolean isGroup();

    /**
     * @hide
     */
    @VisibleForTesting
    public int getThreadId() {
        return mThreadId;
    }

    /**
     * @hide
     */
    public int getThreadType() {
        return isGroup() ? THREAD_TYPE_GROUP : THREAD_TYPE_1_TO_1;
    }
}
