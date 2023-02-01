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

/**
 * A request for TS (transport stream) from broadcast signal.
 */
public final class TsRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_TS;

    public static final @NonNull Parcelable.Creator<TsRequest> CREATOR =
            new Parcelable.Creator<TsRequest>() {
                @Override
                public TsRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TsRequest[] newArray(int size) {
                    return new TsRequest[size];
                }
            };

    private final int mTsPid;

    static @NonNull TsRequest createFromParcelBody(@NonNull Parcel in) {
        return new TsRequest(in);
    }

    public TsRequest(int requestId, @RequestOption int option, int tsPid) {
        super(REQUEST_TYPE, requestId, option);
        mTsPid = tsPid;
    }

    TsRequest(@NonNull Parcel source) {
        super(REQUEST_TYPE, source);
        mTsPid = source.readInt();
    }

    /**
     * Gets the packet identifier (PID) of the TS (transport stream).
     */
    public int getTsPid() {
        return mTsPid;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTsPid);
    }
}
