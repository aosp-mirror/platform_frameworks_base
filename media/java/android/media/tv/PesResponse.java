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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A response for PES from broadcast signal.
 */
public final class PesResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_PES;

    public static final @NonNull Parcelable.Creator<PesResponse> CREATOR =
            new Parcelable.Creator<PesResponse>() {
                @Override
                public PesResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public PesResponse[] newArray(int size) {
                    return new PesResponse[size];
                }
            };

    private final String mSharedFilterToken;

    static PesResponse createFromParcelBody(Parcel in) {
        return new PesResponse(in);
    }

    public PesResponse(int requestId, int sequence, @ResponseResult int responseResult,
            @Nullable String sharedFilterToken) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mSharedFilterToken = sharedFilterToken;
    }

    PesResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        mSharedFilterToken = source.readString();
    }

    /**
     * Gets the token for a shared filter from Tv Input Service.
     */
    @Nullable
    public String getSharedFilterToken() {
        return mSharedFilterToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mSharedFilterToken);
    }
}
