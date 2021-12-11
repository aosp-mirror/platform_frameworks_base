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
public final class SectionRequest extends BroadcastInfoRequest implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int requestType =
            TvInputManager.BROADCAST_INFO_TYPE_SECTION;

    public static final @NonNull Parcelable.Creator<SectionRequest> CREATOR =
            new Parcelable.Creator<SectionRequest>() {
                @Override
                public SectionRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public SectionRequest[] newArray(int size) {
                    return new SectionRequest[size];
                }
            };

    private final int mTsPid;
    private final int mTableId;
    private final Integer mVersion;

    public static SectionRequest createFromParcelBody(Parcel in) {
        return new SectionRequest(in);
    }

    public SectionRequest(int requestId, @RequestOption int option, int tsPid, int tableId,
            Integer version) {
        super(requestType, requestId, option);
        mTsPid = tsPid;
        mTableId = tableId;
        mVersion = version;
    }

    protected SectionRequest(Parcel source) {
        super(requestType, source);
        mTsPid = source.readInt();
        mTableId = source.readInt();
        mVersion = (Integer) source.readValue(Integer.class.getClassLoader());
    }

    public int getTsPid() {
        return mTsPid;
    }

    public int getTableId() {
        return mTableId;
    }

    public Integer getVersion() {
        return mVersion;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTsPid);
        dest.writeInt(mTableId);
        dest.writeValue(mVersion);
    }
}
