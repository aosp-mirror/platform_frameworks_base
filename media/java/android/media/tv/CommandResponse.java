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
public final class CommandResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_TYPE_TV_PROPRIETARY_FUNCTION;

    public static final @NonNull Parcelable.Creator<CommandResponse> CREATOR =
            new Parcelable.Creator<CommandResponse>() {
                @Override
                public CommandResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public CommandResponse[] newArray(int size) {
                    return new CommandResponse[size];
                }
            };

    private final String mResponse;

    public static CommandResponse createFromParcelBody(Parcel in) {
        return new CommandResponse(in);
    }

    public CommandResponse(int requestId, int sequence,
            @ResponseResult int responseResult, String response) {
        super(responseType, requestId, sequence, responseResult);
        mResponse = response;
    }

    protected CommandResponse(Parcel source) {
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
