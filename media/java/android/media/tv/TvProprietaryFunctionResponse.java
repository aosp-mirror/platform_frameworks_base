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
public class TvProprietaryFunctionResponse extends BroadcastInfoResponse implements Parcelable {
    public static final int responseType = BroadcastInfoType.TV_PROPRIETARY_FUNCTION;

    public static final @NonNull Parcelable.Creator<TvProprietaryFunctionResponse> CREATOR =
            new Parcelable.Creator<TvProprietaryFunctionResponse>() {
                @Override
                public TvProprietaryFunctionResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TvProprietaryFunctionResponse[] newArray(int size) {
                    return new TvProprietaryFunctionResponse[size];
                }
            };

    private final String mResponse;

    public static TvProprietaryFunctionResponse createFromParcelBody(Parcel in) {
        return new TvProprietaryFunctionResponse(in);
    }

    public TvProprietaryFunctionResponse(int requestId, int sequence, int responseResult,
            String response) {
        super(responseType, requestId, sequence, responseResult);
        mResponse = response;
    }

    protected TvProprietaryFunctionResponse(Parcel source) {
        super(responseType, source);
        mResponse = source.readString();
    }

    public String getResponse() {
        return mResponse;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mResponse);
    }
}
