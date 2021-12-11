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
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class StreamEventResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_STREAM_EVENT;

    public static final @NonNull Parcelable.Creator<StreamEventResponse> CREATOR =
            new Parcelable.Creator<StreamEventResponse>() {
                @Override
                public StreamEventResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public StreamEventResponse[] newArray(int size) {
                    return new StreamEventResponse[size];
                }
            };

    private final String mName;
    private final String mText;
    private final String mData;
    private final String mStatus;

    public static StreamEventResponse createFromParcelBody(Parcel in) {
        return new StreamEventResponse(in);
    }

    public StreamEventResponse(int requestId, int sequence, @ResponseResult int responseResult,
            String name, String text, String data, String status) {
        super(responseType, requestId, sequence, responseResult);
        mName = name;
        mText = text;
        mData = data;
        mStatus = status;
    }

    protected StreamEventResponse(Parcel source) {
        super(responseType, source);
        mName = source.readString();
        mText = source.readString();
        mData = source.readString();
        mStatus = source.readString();
    }

    public String getName() {
        return mName;
    }

    public String getText() {
        return mText;
    }

    public String getData() {
        return mData;
    }

    public String getStatus() {
        return mStatus;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mName);
        dest.writeString(mText);
        dest.writeString(mData);
        dest.writeString(mStatus);
    }
}
