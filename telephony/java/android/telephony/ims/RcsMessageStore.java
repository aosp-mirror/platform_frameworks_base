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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.net.Uri;

import java.util.List;

/**
 * RcsMessageStore is the application interface to RcsProvider and provides access methods to
 * RCS related database tables.
 *
 * @hide
 */
public class RcsMessageStore {
    RcsControllerCall mRcsControllerCall;

    RcsMessageStore(Context context) {
        mRcsControllerCall = new RcsControllerCall(context);
    }

    /**
     * Returns the first chunk of existing {@link RcsThread}s in the common storage.
     *
     * @param queryParameters Parameters to specify to return a subset of all RcsThreads.
     *                        Passing a value of null will return all threads.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsThreadQueryResult getRcsThreads(@Nullable RcsThreadQueryParams queryParameters)
            throws RcsMessageStoreException {
        return new RcsThreadQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getRcsThreads(queryParameters,
                                callingPackage)));
    }

    /**
     * Returns the next chunk of {@link RcsThread}s in the common storage.
     *
     * @param continuationToken A token to continue the query to get the next chunk. This is
     *                          obtained through {@link RcsThreadQueryResult#getContinuationToken}.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsThreadQueryResult getRcsThreads(@NonNull RcsQueryContinuationToken continuationToken)
            throws RcsMessageStoreException {
        return new RcsThreadQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getRcsThreadsWithToken(continuationToken,
                                callingPackage)));
    }

    /**
     * Returns the first chunk of existing {@link RcsParticipant}s in the common storage.
     *
     * @param queryParameters Parameters to specify to return a subset of all RcsParticipants.
     *                        Passing a value of null will return all participants.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsParticipantQueryResult getRcsParticipants(
            @Nullable RcsParticipantQueryParams queryParameters)
            throws RcsMessageStoreException {
        return new RcsParticipantQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getParticipants(queryParameters,
                                callingPackage)));
    }

    /**
     * Returns the next chunk of {@link RcsParticipant}s in the common storage.
     *
     * @param continuationToken A token to continue the query to get the next chunk. This is
     *                          obtained through
     *                          {@link RcsParticipantQueryResult#getContinuationToken}
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsParticipantQueryResult getRcsParticipants(
            @NonNull RcsQueryContinuationToken continuationToken)
            throws RcsMessageStoreException {
        return new RcsParticipantQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getParticipantsWithToken(continuationToken,
                                callingPackage)));
    }

    /**
     * Returns the first chunk of existing {@link RcsMessage}s in the common storage.
     *
     * @param queryParams Parameters to specify to return a subset of all RcsMessages.
     *                    Passing a value of null will return all messages.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsMessageQueryResult getRcsMessages(
            @Nullable RcsMessageQueryParams queryParams) throws RcsMessageStoreException {
        return new RcsMessageQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getMessages(queryParams, callingPackage)));
    }

    /**
     * Returns the next chunk of {@link RcsMessage}s in the common storage.
     *
     * @param continuationToken A token to continue the query to get the next chunk. This is
     *                          obtained through {@link RcsMessageQueryResult#getContinuationToken}
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsMessageQueryResult getRcsMessages(
            @NonNull RcsQueryContinuationToken continuationToken) throws RcsMessageStoreException {
        return new RcsMessageQueryResult(mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.getMessagesWithToken(continuationToken,
                                callingPackage)));
    }

    /**
     * Returns the first chunk of existing {@link RcsEvent}s in the common storage.
     *
     * @param queryParams Parameters to specify to return a subset of all RcsEvents.
     *                    Passing a value of null will return all events.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsEventQueryResult getRcsEvents(
            @Nullable RcsEventQueryParams queryParams) throws RcsMessageStoreException {
        return mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.getEvents(queryParams, callingPackage))
                .getRcsEventQueryResult(mRcsControllerCall);
    }

    /**
     * Returns the next chunk of {@link RcsEvent}s in the common storage.
     *
     * @param continuationToken A token to continue the query to get the next chunk. This is
     *                          obtained through {@link RcsEventQueryResult#getContinuationToken}.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     */
    @WorkerThread
    @NonNull
    public RcsEventQueryResult getRcsEvents(
            @NonNull RcsQueryContinuationToken continuationToken) throws RcsMessageStoreException {
        return mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.getEventsWithToken(continuationToken,
                        callingPackage))
                .getRcsEventQueryResult(mRcsControllerCall);
    }

    /**
     * Persists an {@link RcsEvent} to common storage.
     *
     * @param rcsEvent The {@link RcsEvent} to persist into storage.
     * @throws RcsMessageStoreException if the query could not be completed on the storage
     * @see RcsGroupThreadNameChangedEvent
     * @see RcsGroupThreadIconChangedEvent
     * @see RcsGroupThreadParticipantJoinedEvent
     * @see RcsGroupThreadParticipantLeftEvent
     * @see RcsParticipantAliasChangedEvent
     */
    @WorkerThread
    @NonNull
    public void persistRcsEvent(RcsEvent rcsEvent) throws RcsMessageStoreException {
        rcsEvent.persist(mRcsControllerCall);
    }

    /**
     * Creates a new 1 to 1 thread with the given participant and persists it in the storage.
     *
     * @param recipient The {@link RcsParticipant} that will receive the messages in this thread.
     * @return The newly created {@link Rcs1To1Thread}
     * @throws RcsMessageStoreException if the thread could not be persisted in the storage
     */
    @WorkerThread
    @NonNull
    public Rcs1To1Thread createRcs1To1Thread(@NonNull RcsParticipant recipient)
            throws RcsMessageStoreException {
        return new Rcs1To1Thread(
                mRcsControllerCall,
                mRcsControllerCall.call(
                        (iRcs, callingPackage) -> iRcs.createRcs1To1Thread(recipient.getId(),
                                callingPackage)));
    }

    /**
     * Creates a new group thread with the given participants and persists it in the storage.
     *
     * @throws RcsMessageStoreException if the thread could not be persisted in the storage
     */
    @WorkerThread
    @NonNull
    public RcsGroupThread createGroupThread(@Nullable List<RcsParticipant> recipients,
            @Nullable String groupName, @Nullable Uri groupIcon) throws RcsMessageStoreException {
        int[] recipientIds = null;
        if (recipients != null) {
            recipientIds = new int[recipients.size()];

            for (int i = 0; i < recipients.size(); i++) {
                recipientIds[i] = recipients.get(i).getId();
            }
        }

        int[] finalRecipientIds = recipientIds;

        int threadId = mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.createGroupThread(finalRecipientIds, groupName,
                        groupIcon, callingPackage));

        return new RcsGroupThread(mRcsControllerCall, threadId);
    }

    /**
     * Delete the given {@link RcsThread} from the storage.
     *
     * @param thread The thread to be deleted.
     * @throws RcsMessageStoreException if the thread could not be deleted from the storage
     */
    @WorkerThread
    public void deleteThread(@NonNull RcsThread thread) throws RcsMessageStoreException {
        if (thread == null) {
            return;
        }

        boolean isDeleteSucceeded = mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.deleteThread(thread.getThreadId(),
                        thread.getThreadType(), callingPackage));

        if (!isDeleteSucceeded) {
            throw new RcsMessageStoreException("Could not delete RcsThread");
        }
    }

    /**
     * Creates a new participant and persists it in the storage.
     *
     * @param canonicalAddress The defining address (e.g. phone number) of the participant.
     * @param alias            The RCS alias for the participant.
     * @throws RcsMessageStoreException if the participant could not be created on the storage
     */
    @WorkerThread
    @NonNull
    public RcsParticipant createRcsParticipant(String canonicalAddress, @Nullable String alias)
            throws RcsMessageStoreException {
        return new RcsParticipant(mRcsControllerCall, mRcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.createRcsParticipant(canonicalAddress, alias,
                        callingPackage)));
    }
}
