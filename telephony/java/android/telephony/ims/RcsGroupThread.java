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
import android.net.Uri;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RcsGroupThread represents a single RCS conversation thread where {@link RcsParticipant}s can join
 * or leave. Please see Section 6 (Group Chat) - GSMA RCC.71 (RCS Universal Profile Service
 * Definition Document)
 */
public class RcsGroupThread extends RcsThread {
    /**
     * Public constructor only for RcsMessageStoreController to initialize new threads.
     *
     * @hide
     */
    public RcsGroupThread(int threadId) {
        super(threadId);
    }

    /**
     * @return Returns {@code true} as this is always a group thread
     */
    @Override
    public boolean isGroup() {
        return true;
    }

    /**
     * @return Returns the given name of this {@link RcsGroupThread}. Please see US6-2 - GSMA RCC.71
     * (RCS Universal Profile Service Definition Document)
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public String getGroupName() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getGroupThreadName(mThreadId));
    }

    /**
     * Sets the name of this {@link RcsGroupThread} and saves it into storage. Please see US6-2 -
     * GSMA RCC.71 (RCS Universal Profile Service Definition Document)
     *
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setGroupName(String groupName) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setGroupThreadName(mThreadId, groupName));
    }

    /**
     * @return Returns a URI that points to the group's icon {@link RcsGroupThread}. Please see
     * US6-2 - GSMA RCC.71 (RCS Universal Profile Service Definition Document)
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    public Uri getGroupIcon() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getGroupThreadIcon(mThreadId));
    }

    /**
     * Sets the icon for this {@link RcsGroupThread} and saves it into storage. Please see US6-2 -
     * GSMA RCC.71 (RCS Universal Profile Service Definition Document)
     *
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setGroupIcon(@Nullable Uri groupIcon) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(iRcs -> iRcs.setGroupThreadIcon(mThreadId, groupIcon));
    }

    /**
     * @return Returns the owner of this thread or {@code null} if there doesn't exist an owner
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public RcsParticipant getOwner() throws RcsMessageStoreException {
        return new RcsParticipant(RcsControllerCall.call(
                iRcs -> iRcs.getGroupThreadOwner(mThreadId)));
    }

    /**
     * Sets the owner of this {@link RcsGroupThread} and saves it into storage. This is intended to
     * be used for selecting a new owner for a group thread if the owner leaves the thread. The
     * owner needs to be in the list of existing participants.
     *
     * @param participant The new owner of the thread. {@code null} values are allowed.
     * @throws RcsMessageStoreException if the operation could not be persisted into storage
     */
    @WorkerThread
    public void setOwner(@Nullable RcsParticipant participant) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setGroupThreadOwner(mThreadId, participant.getId()));
    }

    /**
     * Adds a new {@link RcsParticipant} to this group thread and persists into storage. If the user
     * is actively participating in this {@link RcsGroupThread}, an {@link RcsParticipant} on behalf
     * of them should be added.
     *
     * @param participant The new participant to be added to the thread.
     * @throws RcsMessageStoreException if the operation could not be persisted into storage
     */
    @WorkerThread
    public void addParticipant(@NonNull RcsParticipant participant)
            throws RcsMessageStoreException {
        if (participant == null) {
            return;
        }

        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.addParticipantToGroupThread(mThreadId, participant.getId()));
    }

    /**
     * Removes an {@link RcsParticipant} from this group thread and persists into storage. If the
     * removed participant was the owner of this group, the owner will become null.
     *
     * @throws RcsMessageStoreException if the operation could not be persisted into storage
     */
    @WorkerThread
    public void removeParticipant(@NonNull RcsParticipant participant)
            throws RcsMessageStoreException {
        if (participant == null) {
            return;
        }

        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.removeParticipantFromGroupThread(mThreadId, participant.getId()));
    }

    /**
     * Returns the set of {@link RcsParticipant}s that contribute to this group thread. The
     * returned set does not support modifications, please use
     * {@link RcsGroupThread#addParticipant(RcsParticipant)}
     * and {@link RcsGroupThread#removeParticipant(RcsParticipant)} instead.
     *
     * @return the immutable set of {@link RcsParticipant} in this group thread.
     * @throws RcsMessageStoreException if the values could not be read from the storage
     */
    @WorkerThread
    @NonNull
    public Set<RcsParticipant> getParticipants() throws RcsMessageStoreException {
        RcsParticipantQueryParams queryParameters =
                new RcsParticipantQueryParams.Builder().setThread(this).build();

        RcsParticipantQueryResult queryResult = RcsControllerCall.call(
                iRcs -> iRcs.getParticipants(queryParameters));

        List<RcsParticipant> participantList = queryResult.getParticipants();
        Set<RcsParticipant> participantSet = new LinkedHashSet<>(participantList);
        return Collections.unmodifiableSet(participantSet);
    }

    /**
     * Returns the conference URI for this {@link RcsGroupThread}. Please see 4.4.5.2 - GSMA RCC.53
     * (RCS Device API 1.6 Specification
     *
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @Nullable
    @WorkerThread
    public Uri getConferenceUri() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.getGroupThreadConferenceUri(mThreadId));
    }

    /**
     * Sets the conference URI for this {@link RcsGroupThread} and persists into storage. Please see
     * 4.4.5.2 - GSMA RCC.53 (RCS Device API 1.6 Specification
     *
     * @param conferenceUri The URI as String to be used as the conference URI.
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @Nullable
    @WorkerThread
    public void setConferenceUri(Uri conferenceUri) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.setGroupThreadConferenceUri(mThreadId, conferenceUri));
    }
}
