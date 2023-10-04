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
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A response for Section from broadcast signal.
 */
public final class SectionResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
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

    static SectionResponse createFromParcelBody(Parcel in) {
        return new SectionResponse(in);
    }

    public SectionResponse(int requestId, int sequence, @ResponseResult int responseResult,
            int sessionId, int version, @Nullable Bundle sessionData) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mSessionId = sessionId;
        mVersion = version;
        mSessionData = sessionData;
    }

    SectionResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        mSessionId = source.readInt();
        mVersion = source.readInt();
        mSessionData = source.readBundle();
    }

    /**
     * Gets the Session Id of requested session.
     */
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Gets the Version number of requested session. If it is null, value will be -1.
     * <p>The consistency of version numbers between request and response depends on
     * {@link BroadcastInfoRequest#getOption()}. If the request has RequestOption value
     * REQUEST_OPTION_AUTO_UPDATE, then the response may be set to the latest version which may be
     * different from the version of the request. Otherwise, response with a different version from
     * its request will be considered invalid.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Gets the raw data of session. The sessionData field represents payload data of the session
     * after session header, which includes version and sessionId.
     */
    @NonNull
    public Bundle getSessionData() {
        return mSessionData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mSessionId);
        dest.writeInt(mVersion);
        dest.writeBundle(mSessionData);
    }
}
