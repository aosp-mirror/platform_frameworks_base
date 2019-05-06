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

/**
 * An event that indicates an {@link RcsParticipant}'s alias was changed. Please see US18-2 - GSMA
 * RCC.71 (RCS Universal Profile Service Definition Document)
 *
 * @hide
 */
public final class RcsParticipantAliasChangedEvent extends RcsEvent {
    // The participant that changed their alias
    private final RcsParticipant mParticipant;
    // The new alias of the above participant
    private final String mNewAlias;

    /**
     * Creates a new {@link RcsParticipantAliasChangedEvent}. This event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp The timestamp of when this event happened, in milliseconds passed after
     *                  midnight, January 1st, 1970 UTC
     * @param participant The {@link RcsParticipant} that got their alias changed
     * @param newAlias The new alias the {@link RcsParticipant} has.
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsParticipantAliasChangedEvent(long timestamp, @NonNull RcsParticipant participant,
            @Nullable String newAlias) {
        super(timestamp);
        mParticipant = participant;
        mNewAlias = newAlias;
    }

    /**
     * @return Returns the {@link RcsParticipant} whose alias was changed.
     */
    @NonNull
    public RcsParticipant getParticipant() {
        return mParticipant;
    }

    /**
     * @return Returns the alias of the associated {@link RcsParticipant} after this event happened
     */
    @Nullable
    public String getNewAlias() {
        return mNewAlias;
    }

    /**
     * Persists the event to the data store.
     *
     * @hide - not meant for public use.
     */
    @Override
    void persist(RcsControllerCall rcsControllerCall) throws RcsMessageStoreException {
        rcsControllerCall.call((iRcs, callingPackage) -> iRcs.createParticipantAliasChangedEvent(
                getTimestamp(), getParticipant().getId(), getNewAlias(), callingPackage));
    }
}
