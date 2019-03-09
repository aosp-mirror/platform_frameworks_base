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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.RcsMessage.RcsMessageStatus;

/**
 * An immutable summary of the latest {@link RcsMessage} on an {@link RcsThread}
 *
 * @hide
 */
public final class RcsMessageSnippet implements Parcelable {
    private final String mText;
    private final @RcsMessageStatus int mStatus;
    private final long mTimestamp;

    /**
     * @hide
     */
    public RcsMessageSnippet(String text, @RcsMessageStatus int status, long timestamp) {
        mText = text;
        mStatus = status;
        mTimestamp = timestamp;
    }

    /**
     * @return Returns the text of the {@link RcsMessage} with highest origination timestamp value
     * (i.e. latest) in this thread
     */
    @Nullable
    public String getSnippetText() {
        return mText;
    }

    /**
     * @return Returns the status of the {@link RcsMessage} with highest origination timestamp value
     * (i.e. latest) in this thread
     */
    public @RcsMessageStatus int getSnippetStatus() {
        return mStatus;
    }

    /**
     * @return Returns the timestamp of the {@link RcsMessage} with highest origination timestamp
     * value (i.e. latest) in this thread
     */
    public long getSnippetTimestamp() {
        return mTimestamp;
    }

    private RcsMessageSnippet(Parcel in) {
        mText = in.readString();
        mStatus = in.readInt();
        mTimestamp = in.readLong();
    }

    public static final @android.annotation.NonNull Creator<RcsMessageSnippet> CREATOR =
            new Creator<RcsMessageSnippet>() {
                @Override
                public RcsMessageSnippet createFromParcel(Parcel in) {
                    return new RcsMessageSnippet(in);
                }

                @Override
                public RcsMessageSnippet[] newArray(int size) {
                    return new RcsMessageSnippet[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mText);
        dest.writeInt(mStatus);
        dest.writeLong(mTimestamp);
    }
}
