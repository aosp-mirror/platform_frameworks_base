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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event that indicates an {@link RcsGroupThread}'s name was changed. Please see R6-2-5 - GSMA
 * RCC.71 (RCS Universal Profile Service Definition Document)
 */
public final class RcsGroupThreadNameChangedEvent extends RcsGroupThreadEvent implements
        Parcelable {
    private final String mNewName;

    /**
     * Creates a new {@link RcsGroupThreadNameChangedEvent}. This event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp The timestamp of when this event happened, in milliseconds passed after
     *                  midnight, January 1st, 1970 UTC
     * @param rcsGroupThread The {@link RcsGroupThread} that this event happened on
     * @param originatingParticipant The {@link RcsParticipant} that changed the
     *                               {@link RcsGroupThread}'s icon.
     * @param newName The new name of the {@link RcsGroupThread}
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsGroupThreadNameChangedEvent(long timestamp, @NonNull RcsGroupThread rcsGroupThread,
            @NonNull RcsParticipant originatingParticipant, @Nullable String newName) {
        super(timestamp, rcsGroupThread.getThreadId(), originatingParticipant.getId());
        mNewName = newName;
    }

    /**
     * @hide - internal constructor for queries
     */
    public RcsGroupThreadNameChangedEvent(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId, @Nullable String newName) {
        super(timestamp, rcsGroupThreadId, originatingParticipantId);
        mNewName = newName;
    }

    /**
     * @return Returns the name of this {@link RcsGroupThread} after this
     * {@link RcsGroupThreadNameChangedEvent} happened.
     */
    @Nullable
    public String getNewName() {
        return mNewName;
    }

    /**
     * Persists the event to the data store.
     *
     * @hide - not meant for public use.
     */
    @Override
    public void persist() throws RcsMessageStoreException {
        RcsControllerCall.call(iRcs -> iRcs.createGroupThreadNameChangedEvent(
                getTimestamp(), getRcsGroupThread().getThreadId(),
                getOriginatingParticipant().getId(), mNewName));
    }

    public static final Creator<RcsGroupThreadNameChangedEvent> CREATOR =
            new Creator<RcsGroupThreadNameChangedEvent>() {
                @Override
                public RcsGroupThreadNameChangedEvent createFromParcel(Parcel in) {
                    return new RcsGroupThreadNameChangedEvent(in);
                }

                @Override
                public RcsGroupThreadNameChangedEvent[] newArray(int size) {
                    return new RcsGroupThreadNameChangedEvent[size];
                }
            };

    private RcsGroupThreadNameChangedEvent(Parcel in) {
        super(in);
        mNewName = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mNewName);
    }
}
