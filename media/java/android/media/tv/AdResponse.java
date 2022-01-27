/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An advertisement request which can be sent to TV interactive App service to inform AD status.
 */
public final class AdResponse implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "RESPONSE_TYPE_", value = {
            RESPONSE_TYPE_PLAYING,
            RESPONSE_TYPE_FINISHED,
            RESPONSE_TYPE_STOPPED,
            RESPONSE_TYPE_ERROR
    })
    public @interface ResponseType {}

    public static final int RESPONSE_TYPE_PLAYING = 1;
    public static final int RESPONSE_TYPE_FINISHED = 2;
    public static final int RESPONSE_TYPE_STOPPED = 3;
    public static final int RESPONSE_TYPE_ERROR = 4;

    public static final @NonNull Parcelable.Creator<AdResponse> CREATOR =
            new Parcelable.Creator<AdResponse>() {
                @Override
                public AdResponse createFromParcel(Parcel source) {
                    return new AdResponse(source);
                }

                @Override
                public AdResponse[] newArray(int size) {
                    return new AdResponse[size];
                }
            };

    private final int mId;
    private final @ResponseType int mResponseType;
    private final long mElapsedTime;

    public AdResponse(int id, @ResponseType int responseType, long elapsedTime) {
        mId = id;
        mResponseType = responseType;
        mElapsedTime = elapsedTime;
    }

    private AdResponse(Parcel source) {
        mId = source.readInt();
        mResponseType = source.readInt();
        mElapsedTime = source.readLong();
    }

    /**
     * Gets the ID of AD response.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the response type.
     */
    @ResponseType
    public int getResponseType() {
        return mResponseType;
    }

    /**
     * Gets the playback elapsed time in milliseconds.
     *
     * @return The playback elapsed time. -1 if no valid elapsed time.
     */
    public long getElapsedTimeMillis() {
        return mElapsedTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mResponseType);
        dest.writeLong(mElapsedTime);
    }
}
