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

/**
 * An event that indicates an RCS participant has joined an {@link RcsThread}. Please see US6-3 -
 * GSMA RCC.71 (RCS Universal Profile Service Definition Document)
 *
 * @hide
 */
public final class RcsGroupThreadParticipantJoinedEvent extends RcsGroupThreadEvent {
    private final RcsParticipant mJoinedParticipantId;

    /**
     * Creates a new {@link RcsGroupThreadParticipantJoinedEvent}. This event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp              The timestamp of when this event happened, in milliseconds
     *                               passed after
     *                               midnight, January 1st, 1970 UTC
     * @param rcsGroupThread         The {@link RcsGroupThread} that this event happened on
     * @param originatingParticipant The {@link RcsParticipant} that added or invited the new
     *                               {@link RcsParticipant} into the {@link RcsGroupThread}
     * @param joinedParticipant      The new {@link RcsParticipant} that joined the
     *                               {@link RcsGroupThread}
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsGroupThreadParticipantJoinedEvent(long timestamp,
            @NonNull RcsGroupThread rcsGroupThread, @NonNull RcsParticipant originatingParticipant,
            @NonNull RcsParticipant joinedParticipant) {
        super(timestamp, rcsGroupThread, originatingParticipant);
        mJoinedParticipantId = joinedParticipant;
    }

    /**
     * @return Returns the {@link RcsParticipant} that joined the associated {@link RcsGroupThread}
     */
    public RcsParticipant getJoinedParticipant() {
        return mJoinedParticipantId;
    }

    /**
     * Persists the event to the data store.
     *
     * @hide - not meant for public use.
     */
    @Override
    void persist(RcsControllerCall rcsControllerCall) throws RcsMessageStoreException {
        rcsControllerCall.call(
                (iRcs, callingPackage) -> iRcs.createGroupThreadParticipantJoinedEvent(
                        getTimestamp(),
                        getRcsGroupThread().getThreadId(), getOriginatingParticipant().getId(),
                        getJoinedParticipant().getId(), callingPackage));
    }
}
