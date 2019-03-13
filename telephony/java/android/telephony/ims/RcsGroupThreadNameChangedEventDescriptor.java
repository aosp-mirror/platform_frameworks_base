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
public class RcsGroupThreadNameChangedEventDescriptor extends RcsGroupThreadEventDescriptor {
    private final String mNewName;

    public RcsGroupThreadNameChangedEventDescriptor(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId, @Nullable String newName) {
        super(timestamp, rcsGroupThreadId, originatingParticipantId);
        mNewName = newName;
    }

    @Override
    @VisibleForTesting(visibility = PROTECTED)
    public RcsGroupThreadNameChangedEvent createRcsEvent() {
        return new RcsGroupThreadNameChangedEvent(
                mTimestamp,
                new RcsGroupThread(mRcsGroupThreadId),
                new RcsParticipant(mOriginatingParticipantId),
                mNewName);
    }

    public static final @NonNull Creator<RcsGroupThreadNameChangedEventDescriptor> CREATOR =
            new Creator<RcsGroupThreadNameChangedEventDescriptor>() {
                @Override
                public RcsGroupThreadNameChangedEventDescriptor createFromParcel(Parcel in) {
                    return new RcsGroupThreadNameChangedEventDescriptor(in);
                }

                @Override
                public RcsGroupThreadNameChangedEventDescriptor[] newArray(int size) {
                    return new RcsGroupThreadNameChangedEventDescriptor[size];
                }
            };

    protected RcsGroupThreadNameChangedEventDescriptor(Parcel in) {
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
