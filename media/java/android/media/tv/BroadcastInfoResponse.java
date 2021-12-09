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

import android.os.Parcel;
import android.os.Parcelable;

import android.annotation.NonNull;

/** @hide */
public abstract class BroadcastInfoResponse implements Parcelable {
    // todo: change const declaration to intdef
    public static final int ERROR = 1;
    public static final int OK = 2;
    public static final int CANCEL = 3;

    public static final @NonNull Parcelable.Creator<BroadcastInfoResponse> CREATOR =
            new Parcelable.Creator<BroadcastInfoResponse>() {
                @Override
                public BroadcastInfoResponse createFromParcel(Parcel source) {
                    int type = source.readInt();
                    switch (type) {
                        case BroadcastInfoType.TS:
                            return TsResponse.createFromParcelBody(source);
                        case BroadcastInfoType.TABLE:
                            return TableResponse.createFromParcelBody(source);
                        case BroadcastInfoType.SECTION:
                            return SectionResponse.createFromParcelBody(source);
                        case BroadcastInfoType.PES:
                            return PesResponse.createFromParcelBody(source);
                        case BroadcastInfoType.STREAM_EVENT:
                            return StreamEventResponse.createFromParcelBody(source);
                        case BroadcastInfoType.DSMCC:
                            return DsmccResponse.createFromParcelBody(source);
                        case BroadcastInfoType.TV_PROPRIETARY_FUNCTION:
                            return TvProprietaryFunctionResponse.createFromParcelBody(source);
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

    protected final int mType;
    protected final int mRequestId;
    protected final int mSequence;
    protected final int mResponseResult;

    protected BroadcastInfoResponse(int type, int requestId, int sequence, int responseResult) {
        mType = type;
        mRequestId = requestId;
        mSequence = sequence;
        mResponseResult = responseResult;
    }

    protected BroadcastInfoResponse(int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mSequence = source.readInt();
        mResponseResult = source.readInt();
    }

    public int getType() {
        return mType;
    }

    public int getRequestId() {
        return mRequestId;
    }

    public int getSequence() {
        return mSequence;
    }

    public int getResponseResult() {
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
