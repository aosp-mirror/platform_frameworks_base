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
 * A request for PES from broadcast signal.
 */
public final class PesRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_PES;

    public static final @NonNull Parcelable.Creator<PesRequest> CREATOR =
            new Parcelable.Creator<PesRequest>() {
                @Override
                public PesRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public PesRequest[] newArray(int size) {
                    return new PesRequest[size];
                }
            };

    private final int mTsPid;
    private final int mStreamId;

    static PesRequest createFromParcelBody(Parcel in) {
        return new PesRequest(in);
    }

    public PesRequest(int requestId, @RequestOption int option, int tsPid, int streamId) {
        super(REQUEST_TYPE, requestId, option);
        mTsPid = tsPid;
        mStreamId = streamId;
    }

    PesRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        mTsPid = source.readInt();
        mStreamId = source.readInt();
    }

    /**
     * Gets the packet identifier (PID) of the TS (transport stream).
     */
    public int getTsPid() {
        return mTsPid;
    }

    /**
     * Gets the StreamID of requested PES.
     */
    public int getStreamId() {
        return mStreamId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTsPid);
        dest.writeInt(mStreamId);
    }
}
