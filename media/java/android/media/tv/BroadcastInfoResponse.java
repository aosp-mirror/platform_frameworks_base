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
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A response of {@link BroadcastInfoRequest} for information retrieved from broadcast signal.
 */
@SuppressLint("ParcelNotFinal")
public abstract class BroadcastInfoResponse implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RESPONSE_RESULT_ERROR, RESPONSE_RESULT_OK, RESPONSE_RESULT_CANCEL})
    public @interface ResponseResult {}

    /**
     * Response result: error. This means the request can not be set up successfully.
     */
    public static final int RESPONSE_RESULT_ERROR = 1;
    /**
     * Response result: OK. This means the request is set up successfully and the related responses
     * are normal responses.
     */
    public static final int RESPONSE_RESULT_OK = 2;
    /**
     * Response result: cancel. This means the request has been cancelled.
     */
    public static final int RESPONSE_RESULT_CANCEL = 3;

    public static final @NonNull Parcelable.Creator<BroadcastInfoResponse> CREATOR =
            new Parcelable.Creator<BroadcastInfoResponse>() {
                @Override
                public BroadcastInfoResponse createFromParcel(Parcel source) {
                    @TvInputManager.BroadcastInfoType int type = source.readInt();
                    switch (type) {
                        case TvInputManager.BROADCAST_INFO_TYPE_TS:
                            return TsResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TABLE:
                            return TableResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_SECTION:
                            return SectionResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_PES:
                            return PesResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_STREAM_EVENT:
                            return StreamEventResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_DSMCC:
                            return DsmccResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_COMMAND:
                            return CommandResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TIMELINE:
                            return TimelineResponse.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_SIGNALING_DATA:
                            return SignalingDataResponse.createFromParcelBody(source);
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

    private final @TvInputManager.BroadcastInfoType int mType;
    private final int mRequestId;
    private final int mSequence;
    private final @ResponseResult int mResponseResult;

    BroadcastInfoResponse(@TvInputManager.BroadcastInfoType int type, int requestId,
            int sequence, @ResponseResult int responseResult) {
        mType = type;
        mRequestId = requestId;
        mSequence = sequence;
        mResponseResult = responseResult;
    }

    BroadcastInfoResponse(@TvInputManager.BroadcastInfoType int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mSequence = source.readInt();
        mResponseResult = source.readInt();
    }

    /**
     * Gets the broadcast info type.
     *
     * <p>The type indicates what broadcast information is requested, such as broadcast table,
     * PES (packetized Elementary Stream), TS (transport stream), etc. The type of the
     * request and the related responses should be the same.
     */
    @TvInputManager.BroadcastInfoType
    public int getType() {
        return mType;
    }

    /**
     * Gets the ID of the request.
     *
     * <p>The ID is used to associate the response with the request.
     *
     * @see android.media.tv.BroadcastInfoRequest#getRequestId()
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Gets the sequence number which indicates the order of related responses.
     */
    public int getSequence() {
        return mSequence;
    }

    /**
     * Gets the result for the response.
     *
     * @see #RESPONSE_RESULT_OK
     * @see #RESPONSE_RESULT_ERROR
     * @see #RESPONSE_RESULT_CANCEL
     */
    @ResponseResult
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
