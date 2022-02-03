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
 * A request for the information retrieved from broadcast signal.
 */
@SuppressLint("ParcelNotFinal")
public abstract class BroadcastInfoRequest implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({REQUEST_OPTION_REPEAT, REQUEST_OPTION_AUTO_UPDATE})
    public @interface RequestOption {}

    /**
     * Request option: repeat.
     * <p>With this option, a response is sent when related broadcast information is detected,
     * even if the same information has been sent previously.
     */
    public static final int REQUEST_OPTION_REPEAT = 0;
    /**
     * Request option: auto update.
     * <p>With this option, a response is sent only when broadcast information is detected for the
     * first time, new values are detected.
     */
    public static final int REQUEST_OPTION_AUTO_UPDATE = 1;

    public static final @NonNull Parcelable.Creator<BroadcastInfoRequest> CREATOR =
            new Parcelable.Creator<BroadcastInfoRequest>() {
                @Override
                public BroadcastInfoRequest createFromParcel(Parcel source) {
                    @TvInputManager.BroadcastInfoType int type = source.readInt();
                    switch (type) {
                        case TvInputManager.BROADCAST_INFO_TYPE_TS:
                            return TsRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TABLE:
                            return TableRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_SECTION:
                            return SectionRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_PES:
                            return PesRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_STREAM_EVENT:
                            return StreamEventRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_DSMCC:
                            return DsmccRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_COMMAND:
                            return CommandRequest.createFromParcelBody(source);
                        case TvInputManager.BROADCAST_INFO_TYPE_TIMELINE:
                            return TimelineRequest.createFromParcelBody(source);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected broadcast info request type (value "
                                            + type + ") in parcel.");
                    }
                }

                @Override
                public BroadcastInfoRequest[] newArray(int size) {
                    return new BroadcastInfoRequest[size];
                }
            };

    private final @TvInputManager.BroadcastInfoType int mType;
    private final int mRequestId;
    private final @RequestOption int mOption;

    BroadcastInfoRequest(@TvInputManager.BroadcastInfoType int type,
            int requestId, @RequestOption int option) {
        mType = type;
        mRequestId = requestId;
        mOption = option;
    }

    BroadcastInfoRequest(@TvInputManager.BroadcastInfoType int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mOption = source.readInt();
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
     * @see android.media.tv.BroadcastInfoResponse#getRequestId()
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Gets the request option of the request.
     *
     * @see #REQUEST_OPTION_REPEAT
     * @see #REQUEST_OPTION_AUTO_UPDATE
     */
    @RequestOption
    public int getOption() {
        return mOption;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mRequestId);
        dest.writeInt(mOption);
    }
}
