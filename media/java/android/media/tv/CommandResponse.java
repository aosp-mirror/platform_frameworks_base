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
 * A response for command from broadcast signal.
 */
public final class CommandResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_COMMAND;

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

    static CommandResponse createFromParcelBody(Parcel in) {
        return new CommandResponse(in);
    }

    public CommandResponse(int requestId, int sequence,
            @ResponseResult int responseResult, @Nullable String response) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mResponse = response;
    }

    CommandResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        mResponse = source.readString();
    }

    /**
     * Gets the response of the command.
     * It could be serialized from some formats, such as JSON, XML, etc.
     */
    @Nullable
    public String getResponse() {
        return mResponse;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mResponse);
    }
}
