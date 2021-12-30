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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
public final class AdResponse implements Parcelable {
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "RESPONSE_TYPE_", value = {
            RESPONSE_TYPE_PLAYING,
            RESPONSE_TYPE_FINISHED,
            RESPONSE_TYPE_STOPPED,
            RESPONSE_TYPE_ERROR
    })
    public @interface ResponseType {}

    public static final String RESPONSE_TYPE_PLAYING = "PLAYING";
    public static final String RESPONSE_TYPE_FINISHED = "FINISHED";
    public static final String RESPONSE_TYPE_STOPPED = "STOPPED";
    public static final String RESPONSE_TYPE_ERROR = "ERROR";

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
    private final @ResponseType String mResponseType;
    private final Long mElapsedTime;

    public AdResponse(int id, @ResponseType String responseType, @Nullable Long elapsedTime) {
        mId = id;
        mResponseType = responseType;
        mElapsedTime = elapsedTime;
    }

    private AdResponse(Parcel source) {
        mId = source.readInt();
        mResponseType = source.readString();
        mElapsedTime = (Long) source.readValue(Long.class.getClassLoader());
    }

    public int getId() {
        return mId;
    }

    public @ResponseType String getResponseType() {
        return mResponseType;
    }

    public Long getElapsedTime() {
        return mElapsedTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mResponseType);
        dest.writeValue(mElapsedTime);
    }
}
