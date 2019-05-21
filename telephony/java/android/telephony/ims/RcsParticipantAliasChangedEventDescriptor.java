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
import android.annotation.Nullable;
import android.os.Parcel;

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide - used only for internal communication with the ircs service
 */
public class RcsParticipantAliasChangedEventDescriptor extends RcsEventDescriptor {
    // The ID of the participant that changed their alias
    protected int mParticipantId;
    // The new alias of the above participant
    protected String mNewAlias;

    public RcsParticipantAliasChangedEventDescriptor(long timestamp, int participantId,
            @Nullable String newAlias) {
        super(timestamp);
        mParticipantId = participantId;
        mNewAlias = newAlias;
    }

    @Override
    @VisibleForTesting(visibility = PROTECTED)
    public RcsParticipantAliasChangedEvent createRcsEvent(RcsControllerCall rcsControllerCall) {
        return new RcsParticipantAliasChangedEvent(
                mTimestamp, new RcsParticipant(rcsControllerCall, mParticipantId), mNewAlias);
    }

    public static final @NonNull Creator<RcsParticipantAliasChangedEventDescriptor> CREATOR =
            new Creator<RcsParticipantAliasChangedEventDescriptor>() {
                @Override
                public RcsParticipantAliasChangedEventDescriptor createFromParcel(Parcel in) {
                    return new RcsParticipantAliasChangedEventDescriptor(in);
                }

                @Override
                public RcsParticipantAliasChangedEventDescriptor[] newArray(int size) {
                    return new RcsParticipantAliasChangedEventDescriptor[size];
                }
            };

    protected RcsParticipantAliasChangedEventDescriptor(Parcel in) {
        super(in);
        mNewAlias = in.readString();
        mParticipantId = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mNewAlias);
        dest.writeInt(mParticipantId);
    }
}
