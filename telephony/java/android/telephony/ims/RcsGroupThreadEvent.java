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
import android.os.Parcel;

/**
 * An event that happened on an {@link RcsGroupThread}.
 */
public abstract class RcsGroupThreadEvent extends RcsEvent {
    private final int mRcsGroupThreadId;
    private final int mOriginatingParticipantId;

    RcsGroupThreadEvent(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId) {
        super(timestamp);
        mRcsGroupThreadId = rcsGroupThreadId;
        mOriginatingParticipantId = originatingParticipantId;
    }

    /**
     * @return Returns the {@link RcsGroupThread} that this event happened on.
     */
    @NonNull
    public RcsGroupThread getRcsGroupThread() {
        return new RcsGroupThread(mRcsGroupThreadId);
    }

    /**
     * @return Returns the {@link RcsParticipant} that performed the event.
     */
    @NonNull
    public RcsParticipant getOriginatingParticipant() {
        return new RcsParticipant(mOriginatingParticipantId);
    }

    /**
     * @hide
     */
    RcsGroupThreadEvent(Parcel in) {
        super(in);
        mRcsGroupThreadId = in.readInt();
        mOriginatingParticipantId = in.readInt();
    }

    /**
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mRcsGroupThreadId);
        dest.writeInt(mOriginatingParticipantId);
    }
}
