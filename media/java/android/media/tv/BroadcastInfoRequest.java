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
public abstract class BroadcastInfoRequest implements Parcelable {

    // todo: change const declaration to intdef
    public static final int REQUEST_OPTION_REPEAT = 11;
    public static final int REQUEST_OPTION_AUTO_UPDATE = 12;

    public static final @NonNull Parcelable.Creator<BroadcastInfoRequest> CREATOR =
            new Parcelable.Creator<BroadcastInfoRequest>() {
                @Override
                public BroadcastInfoRequest createFromParcel(Parcel source) {
                    int type = source.readInt();
                    switch (type) {
                        case BroadcastInfoType.TS:
                            return TsRequest.createFromParcelBody(source);
                        case BroadcastInfoType.TABLE:
                            return TableRequest.createFromParcelBody(source);
                        case BroadcastInfoType.SECTION:
                            return SectionRequest.createFromParcelBody(source);
                        case BroadcastInfoType.PES:
                            return PesRequest.createFromParcelBody(source);
                        case BroadcastInfoType.STREAM_EVENT:
                            return StreamEventRequest.createFromParcelBody(source);
                        case BroadcastInfoType.DSMCC:
                            return DsmccRequest.createFromParcelBody(source);
                        case BroadcastInfoType.TV_PROPRIETARY_FUNCTION:
                            return TvProprietaryFunctionRequest.createFromParcelBody(source);
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

    protected final int mType;
    protected final int mRequestId;
    protected final int mOption;

    protected BroadcastInfoRequest(int type, int requestId, int option) {
        mType = type;
        mRequestId = requestId;
        mOption = option;
    }

    protected BroadcastInfoRequest(int type, Parcel source) {
        mType = type;
        mRequestId = source.readInt();
        mOption = source.readInt();
    }

    public int getType() {
        return mType;
    }

    public int getRequestId() {
        return mRequestId;
    }

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
