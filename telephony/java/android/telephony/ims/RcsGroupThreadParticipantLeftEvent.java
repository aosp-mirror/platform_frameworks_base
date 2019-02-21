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
 * An event that indicates an RCS participant has left an {@link RcsThread}. Please see US6-23 -
 * GSMA RCC.71 (RCS Universal Profile Service Definition Document)
 */
public final class RcsGroupThreadParticipantLeftEvent extends RcsGroupThreadEvent {
    private RcsParticipant mLeavingParticipant;

    /**
     * Creates a new {@link RcsGroupThreadParticipantLeftEvent}. his event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp The timestamp of when this event happened, in milliseconds passed after
     *                  midnight, January 1st, 1970 UTC
     * @param rcsGroupThread The {@link RcsGroupThread} that this event happened on
     * @param originatingParticipant The {@link RcsParticipant} that removed the
     *                               {@link RcsParticipant} from the {@link RcsGroupThread}. It is
     *                               possible that originatingParticipant and leavingParticipant are
     *                               the same (i.e. {@link RcsParticipant} left the group
     *                               themselves)
     * @param leavingParticipant The {@link RcsParticipant} that left the {@link RcsGroupThread}
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsGroupThreadParticipantLeftEvent(long timestamp,
            @NonNull RcsGroupThread rcsGroupThread, @NonNull RcsParticipant originatingParticipant,
            @NonNull RcsParticipant leavingParticipant) {
        super(timestamp, rcsGroupThread.getThreadId(), originatingParticipant.getId());
        mLeavingParticipant = leavingParticipant;
    }

    /**
     * @return Returns the {@link RcsParticipant} that left the associated {@link RcsGroupThread}
     * after this {@link RcsGroupThreadParticipantLeftEvent} happened.
     */
    @NonNull
    public RcsParticipant getLeavingParticipant() {
        return mLeavingParticipant;
    }

    @Override
    public void persist() throws RcsMessageStoreException {
        RcsControllerCall.call(
                iRcs -> iRcs.createGroupThreadParticipantLeftEvent(getTimestamp(),
                        getRcsGroupThread().getThreadId(), getOriginatingParticipant().getId(),
                        getLeavingParticipant().getId()));
    }
}
