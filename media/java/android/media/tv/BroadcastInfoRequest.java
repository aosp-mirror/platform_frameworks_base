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
    protected static final int PARCEL_TOKEN_TS_REQUEST = 1;

    public static final @NonNull Parcelable.Creator<BroadcastInfoRequest> CREATOR =
            new Parcelable.Creator<BroadcastInfoRequest>() {
                @Override
                public BroadcastInfoRequest createFromParcel(Parcel source) {
                    int token = source.readInt();
                    switch (token) {
                        case PARCEL_TOKEN_TS_REQUEST:
                            return TsRequest.createFromParcelBody(source);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected broadcast info request type token in parcel.");
                    }
                }

                @Override
                public BroadcastInfoRequest[] newArray(int size) {
                    return new BroadcastInfoRequest[size];
                }
            };

    int requestId;

    public BroadcastInfoRequest(int requestId) {
        this.requestId = requestId;
    }

    protected BroadcastInfoRequest(Parcel source) {
        requestId = source.readInt();
    }

    public int getRequestId() {
        return requestId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(requestId);
    }
}
