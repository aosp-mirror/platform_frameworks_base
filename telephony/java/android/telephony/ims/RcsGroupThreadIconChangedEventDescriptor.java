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
import android.net.Uri;
import android.os.Parcel;

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide - used only for internal communication with the ircs service
 */
public class RcsGroupThreadIconChangedEventDescriptor extends RcsGroupThreadEventDescriptor {
    private final Uri mNewIcon;

    public RcsGroupThreadIconChangedEventDescriptor(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId, @Nullable Uri newIcon) {
        super(timestamp, rcsGroupThreadId, originatingParticipantId);
        mNewIcon = newIcon;
    }

    @Override
    @VisibleForTesting(visibility = PROTECTED)
    public RcsGroupThreadIconChangedEvent createRcsEvent(RcsControllerCall rcsControllerCall) {
        return new RcsGroupThreadIconChangedEvent(mTimestamp,
                new RcsGroupThread(rcsControllerCall, mRcsGroupThreadId),
                new RcsParticipant(rcsControllerCall, mOriginatingParticipantId), mNewIcon);
    }

    public static final @NonNull Creator<RcsGroupThreadIconChangedEventDescriptor> CREATOR =
            new Creator<RcsGroupThreadIconChangedEventDescriptor>() {
                @Override
                public RcsGroupThreadIconChangedEventDescriptor createFromParcel(Parcel in) {
                    return new RcsGroupThreadIconChangedEventDescriptor(in);
                }

                @Override
                public RcsGroupThreadIconChangedEventDescriptor[] newArray(int size) {
                    return new RcsGroupThreadIconChangedEventDescriptor[size];
                }
            };

    protected RcsGroupThreadIconChangedEventDescriptor(Parcel in) {
        super(in);
        mNewIcon = in.readParcelable(Uri.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeParcelable(mNewIcon, flags);
    }
}
