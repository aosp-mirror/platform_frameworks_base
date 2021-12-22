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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class SectionResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_TYPE_SECTION;

    public static final @NonNull Parcelable.Creator<SectionResponse> CREATOR =
            new Parcelable.Creator<SectionResponse>() {
                @Override
                public SectionResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public SectionResponse[] newArray(int size) {
                    return new SectionResponse[size];
                }
            };

    private final int mSessionId;
    private final int mVersion;
    private final Bundle mSessionData;

    public static SectionResponse createFromParcelBody(Parcel in) {
        return new SectionResponse(in);
    }

    public SectionResponse(int requestId, int sequence, @ResponseResult int responseResult,
            int sessionId, int version, Bundle sessionData) {
        super(responseType, requestId, sequence, responseResult);
        mSessionId = sessionId;
        mVersion = version;
        mSessionData = sessionData;
    }

    protected SectionResponse(Parcel source) {
        super(responseType, source);
        mSessionId = source.readInt();
        mVersion = source.readInt();
        mSessionData = source.readBundle();
    }

    public int getSessionId() {
        return mSessionId;
    }

    public int getVersion() {
        return mVersion;
    }

    public Bundle getSessionData() {
        return mSessionData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mSessionId);
        dest.writeInt(mVersion);
        dest.writeBundle(mSessionData);
    }
}
