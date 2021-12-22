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
public final class TsResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_TYPE_TS;

    public static final @NonNull Parcelable.Creator<TsResponse> CREATOR =
            new Parcelable.Creator<TsResponse>() {
                @Override
                public TsResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TsResponse[] newArray(int size) {
                    return new TsResponse[size];
                }
            };

    private final String mSharedFilterToken;

    public static TsResponse createFromParcelBody(Parcel in) {
        return new TsResponse(in);
    }

    public TsResponse(int requestId, int sequence, @ResponseResult int responseResult,
            String sharedFilterToken) {
        super(responseType, requestId, sequence, responseResult);
        this.mSharedFilterToken = sharedFilterToken;
    }

    protected TsResponse(Parcel source) {
        super(responseType, source);
        mSharedFilterToken = source.readString();
    }

    public String getSharedFilterToken() {
        return mSharedFilterToken;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mSharedFilterToken);
    }
}
