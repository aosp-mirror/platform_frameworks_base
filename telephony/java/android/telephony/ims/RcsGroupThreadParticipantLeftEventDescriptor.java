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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PROTECTED;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide - used only for internal communication with the ircs service
 */
public class RcsGroupThreadParticipantLeftEventDescriptor extends RcsGroupThreadEventDescriptor {
    private int mLeavingParticipantId;

    public RcsGroupThreadParticipantLeftEventDescriptor(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId, int leavingParticipantId) {
        super(timestamp, rcsGroupThreadId, originatingParticipantId);
        mLeavingParticipantId = leavingParticipantId;
    }

    @Override
    @VisibleForTesting(visibility = PROTECTED)
    public RcsGroupThreadParticipantLeftEvent createRcsEvent() {
        return new RcsGroupThreadParticipantLeftEvent(
                mTimestamp,
                new RcsGroupThread(mRcsGroupThreadId),
                new RcsParticipant(mOriginatingParticipantId),
                new RcsParticipant(mLeavingParticipantId));
    }

    @NonNull
    public static final Parcelable.Creator<RcsGroupThreadParticipantLeftEventDescriptor> CREATOR =
            new Creator<RcsGroupThreadParticipantLeftEventDescriptor>() {
                @Override
                public RcsGroupThreadParticipantLeftEventDescriptor createFromParcel(Parcel in) {
                    return new RcsGroupThreadParticipantLeftEventDescriptor(in);
                }

                @Override
                public RcsGroupThreadParticipantLeftEventDescriptor[] newArray(int size) {
                    return new RcsGroupThreadParticipantLeftEventDescriptor[size];
                }
            };

    protected RcsGroupThreadParticipantLeftEventDescriptor(Parcel in) {
        super(in);
        mLeavingParticipantId = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mLeavingParticipantId);
    }
}
