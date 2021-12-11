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

/** @hide */
public abstract class BroadcastInfoResponse implements Parcelable {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RESPONSE_RESULT_ERROR, RESPONSE_RESULT_OK, RESPONSE_RESULT_CANCEL})
    public @interface ResponseResult {}

    public static final int RESPONSE_RESULT_ERROR = 1;
    public static final int RESPONSE_RESULT_OK = 2;
    public static final int RESPONSE_RESULT_CANCEL = 3;

    public static final @NonNull Parcelable.Creator<BroadcastInfoResponse> CREATOR =
            new Parcelable.Creator<BroadcastInfoResponse>() {
                @Override
                public BroadcastInfoResponse createFromParcel(Parcel source) {
                    @TvInputManager.BroadcastInfoType int type = source.readInt();
                    switch (type) {
                        case TvInputManager.BROADCAST_INFO_TYPE_TS:
                            return TsResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_SECTION:
                            return SectionResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_PES:
                            return PesResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_STREAM_EVENT:
                            return StreamEventResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_DSMCC:
                            return DsmccResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TV_PROPRIETARY_FUNCTION:
                            return CommandResponse.createFromParcelBody(source);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected broadcast info response type (value "
                                            + type + ") in parcel.");
                    }
                }

                @Override
                public BroadcastInfoResponse[] newArray(int size) {
                    return new BroadcastInfoResponse[size];
                }
            };

    protected final @TvInputManager.BroadcastInfoType int mType;
    protected final int mRequestId;
    protected final int mSequence;
    protected final @ResponseResult int mResponseResult;

    protected BroadcastInfoResponse(@TvInputManager.BroadcastInfoType int type, int requestId,
            int sequence, @ResponseResult int responseResult) {
        mType = type;
        mRequestId = requestId;
        mSequence = sequence;
        mResponseResult = responseResult;
    }

    protected BroadcastInfoResponse(@TvInputManager.BroadcastInfoType int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mSequence = source.readInt();
        mResponseResult = source.readInt();
    }

    public @TvInputManager.BroadcastInfoType int getType() {
        return mType;
    }

    public int getRequestId() {
        return mRequestId;
    }

    public int getSequence() {
        return mSequence;
    }

    public @ResponseResult int getResponseResult() {
        return mResponseResult;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mRequestId);
        dest.writeInt(mSequence);
        dest.writeInt(mResponseResult);
    }
}
