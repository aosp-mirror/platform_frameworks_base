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
public class TsRequest extends BroadcastInfoRequest implements Parcelable {
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

    int tsPid;

    public static TsRequest createFromParcelBody(Parcel in) {
        return new TsRequest(in);
    }

    public TsRequest(int requestId, int tsPid) {
        super(requestId);
        this.tsPid = tsPid;
    }

    protected TsRequest(Parcel source) {
        super(source);
        tsPid = source.readInt();
    }

    public int getTsPid() {
        return tsPid;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(PARCEL_TOKEN_TS_REQUEST);
        super.writeToParcel(dest, flags);
        dest.writeInt(tsPid);
    }
}
